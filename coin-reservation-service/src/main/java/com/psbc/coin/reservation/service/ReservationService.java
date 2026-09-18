package com.psbc.coin.reservation.service;

import com.psbc.coin.common.constant.CommonConstants;
import com.psbc.coin.common.dto.ReservationResultMessage;
import com.psbc.coin.common.result.Result;
import com.psbc.coin.common.result.ResultCode;
import com.psbc.coin.common.util.JsonUtils;
import com.psbc.coin.common.util.SnowflakeIdGenerator;
import com.psbc.coin.reservation.config.ActivityWindowCache;
import com.psbc.coin.reservation.config.DegradeManager;
import com.psbc.coin.reservation.dto.BranchStockView;
import com.psbc.coin.reservation.dto.ReservationSubmitRequest;
import com.psbc.coin.reservation.dto.ReservationSubmitResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 预约核心服务（普通纪念币：先到先得、约满为止）。
 *
 * <p>与真实业务对齐：<b>提交那一刻即占用所选网点额度，并当场返回预约号</b>。
 * 因此库存扣减就从「提交」这一步同步完成，不再有独立的抽签/分配阶段。
 *
 * <p>峰值主链路只依赖 Redis + Kafka，不碰数据库：
 * <ol>
 *   <li>活动时间窗校验（读本地缓存，见 {@link ActivityWindowCache}）</li>
 *   <li>幂等去重（一次性 token）</li>
 *   <li>实名资格校验</li>
 *   <li>Lua 原子扣「所选网点」额度 + 限购</li>
 *   <li>记在途单（对账依据）→ 发 Kafka 异步落库</li>
 *   <li>返回预约号 + 该网点剩余额度</li>
 * </ol>
 *
 * <p>用户看到「预约成功」的依据是第 4 步的 Redis 扣减（额度的权威持有者）；
 * 第 5 步的落库是异步最终一致的镜像，由 {@code ReservationReconcileTask} 兜底。
 */
@Slf4j
@Service
public class ReservationService {

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> deductStockScript;
    private final DefaultRedisScript<Long> compensateStockScript;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final SnowflakeIdGenerator idGenerator;
    private final DegradeManager degradeManager;
    private final DbDirectReservationService dbDirectReservationService;
    private final ActivityWindowCache activityWindowCache;

    @Value("${coin.stock-shards:10}")
    private int stockShards;

    @Value("${coin.idempotent-ttl-minutes:10}")
    private long idempotentTtlMinutes;

    public ReservationService(StringRedisTemplate redis,
                              DefaultRedisScript<Long> deductStockScript,
                              DefaultRedisScript<Long> compensateStockScript,
                              KafkaTemplate<String, String> kafkaTemplate,
                              SnowflakeIdGenerator idGenerator,
                              DegradeManager degradeManager,
                              DbDirectReservationService dbDirectReservationService,
                              ActivityWindowCache activityWindowCache) {
        this.redis = redis;
        this.deductStockScript = deductStockScript;
        this.compensateStockScript = compensateStockScript;
        this.kafkaTemplate = kafkaTemplate;
        this.idGenerator = idGenerator;
        this.degradeManager = degradeManager;
        this.dbDirectReservationService = dbDirectReservationService;
        this.activityWindowCache = activityWindowCache;
    }

    /**
     * 提交预约（先到先得）：成功即代表额度已占用，同步返回预约号。
     *
     * <p>失败一律给出明确原因，供前端引导用户「换网点」或「稍后重试」：
     * {@code STOCK_EMPTY} 该网点已约满 / {@code LIMIT_EXCEEDED} 已约过（重复预约无效）
     * / {@code DUPLICATE_SUBMIT} 重复提交 / {@code NOT_QUALIFIED} 无资格
     * / {@code ACTIVITY_NOT_STARTED}、{@code ACTIVITY_ENDED} 不在预约期内。
     */
    public Result<ReservationSubmitResponse> submit(ReservationSubmitRequest req) {
        // ① 活动时间窗：开闸前 / 截止后一律不受理
        ResultCode windowError = activityWindowCache.check(req.getProductId(), LocalDateTime.now());
        if (windowError != null) {
            return Result.fail(windowError);
        }

        // ② 降级模式：Redis 不可用（或手动开关打开）→ 走 DB 直扣（同为同步返回结果）
        if (degradeManager.isDbDirectMode()) {
            return dbDirectReservationService.submitDirect(req);
        }

        String idemKey = CommonConstants.KEY_IDEMPOTENT + ":" + req.getToken();

        // ===== 阶段 A：幂等占位 + 资格校验 =====
        // 此阶段尚未触碰额度，即使 Redis 抛异常也可安全降级为 DB 直扣。
        try {
            Boolean set = redis.opsForValue().setIfAbsent(idemKey, "1", Duration.ofMinutes(idempotentTtlMinutes));
            if (!Boolean.TRUE.equals(set)) {
                return Result.fail(ResultCode.DUPLICATE_SUBMIT);
            }

            String qualKey = CommonConstants.KEY_QUALIFICATION + ":" + req.getUserId();
            if (!Boolean.TRUE.equals(redis.hasKey(qualKey))) {
                releaseIdempotent(idemKey);
                return Result.fail(ResultCode.NOT_QUALIFIED);
            }
        } catch (Exception e) {
            if (isRedisUnavailable(e)) {
                log.error("【降级】Redis 异常，转 DB 直扣: {}", e.getMessage());
                degradeManager.markRedisDown();
                return dbDirectReservationService.submitDirect(req);
            }
            throw e;
        }

        // ===== 阶段 B：扣额度及之后 =====
        // 从这里开始**绝不再降级**：Lua 可能已在服务端执行成功、只是响应超时丢失，
        // 若此时转 DB 直扣，同一用户会在 Redis 和 DB 各占一份额度 —— 重复预约。
        StockResult sr;
        try {
            sr = deductStock(req.getProductId(), req.getBranchId(), req.getIdCard());
        } catch (Exception e) {
            log.error("扣减额度异常，不降级以免重复预约（保留幂等键，请引导用户查询预约结果）: token={}",
                    req.getToken(), e);
            return Result.fail(ResultCode.SYSTEM_ERROR);
        }

        if (sr.code() != 1) {
            // 失败必须释放幂等键，否则用户换网点重试时会被「请勿重复提交」挡住 10 分钟
            releaseIdempotent(idemKey);
            return Result.fail(sr.code() == -2 ? ResultCode.LIMIT_EXCEEDED : ResultCode.STOCK_EMPTY);
        }

        // 额度已占用。以下步骤都做了异常隔离，失败只影响响应丰富度，不影响「已约上」这个事实。
        String orderNo = String.valueOf(idGenerator.nextId());
        Long sequenceNo = safeNextSequence(req.getProductId(), orderNo);
        recordInFlightAndSend(req, orderNo, sequenceNo, sr.shard());
        Long branchRemain = safeBranchRemain(req.getProductId(), req.getBranchId());

        log.info("预约成功: orderNo={}, productId={}, branchId={}, sequenceNo={}, branchRemain={}",
                orderNo, req.getProductId(), req.getBranchId(), sequenceNo, branchRemain);
        return Result.success(new ReservationSubmitResponse(
                orderNo, sequenceNo, req.getProductId(), req.getBranchId(),
                branchRemain, CommonConstants.STATUS_RESERVED));
    }

    /** 预约顺序号（该产品第 N 位预约成功者）。非关键字段，取号失败不影响预约结果。 */
    private Long safeNextSequence(Long productId, String orderNo) {
        try {
            return redis.opsForValue().increment(CommonConstants.KEY_SEQ + ":" + productId);
        } catch (Exception e) {
            log.warn("取预约顺序号失败（不影响预约）: orderNo={}, err={}", orderNo, e.getMessage());
            return null;
        }
    }

    /** 网点剩余额度。仅用于给前端提示，查询失败不影响预约结果。 */
    private Long safeBranchRemain(Long productId, Long branchId) {
        try {
            return remainingStock(productId, branchId);
        } catch (Exception e) {
            log.warn("查询网点剩余额度失败（不影响预约）: productId={}, branchId={}, err={}",
                    productId, branchId, e.getMessage());
            return null;
        }
    }

    /** 判断异常是否为 Redis 不可用（连接失败 / 超时等数据访问异常） */
    private boolean isRedisUnavailable(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof DataAccessException || cur.getClass().getName().contains("Redis")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /** 释放幂等键，使用户在失败后可以立即重试（换网点 / 稍后再来） */
    private void releaseIdempotent(String idemKey) {
        try {
            redis.delete(idemKey);
        } catch (Exception e) {
            log.warn("释放幂等键失败（用户可能需等待 {} 分钟后才能重试）: {}", idempotentTtlMinutes, e.getMessage());
        }
    }

    /**
     * 记在途单 + 异步发 Kafka 落库。
     *
     * <p>顺序很关键：<b>先记在途单再投递</b>。这样即使本进程在扣额后、投递前崩溃，
     * 对账任务也能凭在途单发现「扣了额度却没有落库」并回补额度。
     */
    private void recordInFlightAndSend(ReservationSubmitRequest req, String orderNo, Long sequenceNo, int shard) {
        ReservationResultMessage msg = new ReservationResultMessage(
                UUID.randomUUID().toString(), orderNo, req.getProductId(), req.getBranchId(),
                req.getUserId(), req.getIdCard(), sequenceNo, shard, System.currentTimeMillis());
        String json = JsonUtils.toJson(msg);
        String pendingKey = CommonConstants.KEY_PENDING + ":" + req.getProductId();

        try {
            redis.opsForZSet().add(pendingKey, json, System.currentTimeMillis());
        } catch (Exception e) {
            log.error("记录在途单失败，对账将无法追踪该单: orderNo={}", orderNo, e);
        }

        try {
            kafkaTemplate.send(CommonConstants.TOPIC_RESV_RESULT, orderNo, json)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            // future 异步失败：回补额度 + 撤在途单
                            log.error("Kafka 投递失败，回补额度: orderNo={}", orderNo, ex);
                            rollback(orderNo, req.getProductId(), req.getBranchId(), req.getIdCard(),
                                    shard, pendingKey, json);
                        }
                    });
        } catch (Exception e) {
            // send() 同步抛异常（如 max.block.ms 超时）：回补额度 + 撤在途单
            log.error("Kafka send 同步失败，回补额度: orderNo={}", orderNo, e);
            rollback(orderNo, req.getProductId(), req.getBranchId(), req.getIdCard(), shard, pendingKey, json);
        }
    }

    /** 投递失败后的回滚：幂等回补额度，成功后再撤在途单（顺序不可颠倒，否则对账会重复回补） */
    private void rollback(String orderNo, Long productId, Long branchId, String idCard,
                          int shard, String pendingKey, String json) {
        boolean compensated = compensateOnce(orderNo, productId, branchId, idCard, shard);
        if (!compensated) {
            log.warn("额度回补未执行（已回补过或回补失败），保留在途单交由对账处理: orderNo={}", orderNo);
            return;
        }
        try {
            redis.opsForZSet().remove(pendingKey, json);
        } catch (Exception e) {
            log.warn("撤在途单失败（因已回补过，对账不会重复回补，仅多一次无副作用检查）: orderNo={}", orderNo);
        }
    }

    /**
     * 幂等回补：同一 orderNo 只会真正回补一次。
     *
     * <p>Kafka 投递失败回调与对账任务可能同时判定同一单失败，若无去重会重复 +1 造成超发。
     *
     * @return true 表示本次真正执行了回补
     */
    public boolean compensateOnce(String orderNo, Long productId, Long branchId, String idCard, int shard) {
        String guardKey = CommonConstants.KEY_COMPENSATED + ":" + orderNo;
        Boolean first;
        try {
            first = redis.opsForValue().setIfAbsent(guardKey, "1", Duration.ofHours(24));
        } catch (Exception e) {
            log.error("回补去重标记失败，跳过本次回补（交由对账重试）: orderNo={}", orderNo, e);
            return false;
        }
        if (!Boolean.TRUE.equals(first)) {
            log.debug("该单已回补过，跳过: orderNo={}", orderNo);
            return false;
        }
        try {
            compensateStock(productId, branchId, idCard, shard);
            return true;
        } catch (Exception e) {
            // 标记已写下但回补失败 → 删除标记，让对账任务能够重试
            try {
                redis.delete(guardKey);
            } catch (Exception ignore) {
                // 标记删除失败时，该单最多漏补一次，由库存对账人工介入
            }
            log.error("额度回补失败: orderNo={}", orderNo, e);
            return false;
        }
    }

    /**
     * 各网点剩余额度（供前端展示余量，让用户避开已约满的网点）。
     *
     * <p>网点清单由 product-service 在 init-stock 时预热进 Redis，此处全程只读 Redis。
     */
    public List<BranchStockView> branchStocks(Long productId) {
        Set<String> members = redis.opsForSet().members(CommonConstants.KEY_BRANCHES + ":" + productId);
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        List<Long> branchIds = new ArrayList<>(members.size());
        for (String m : members) {
            try {
                branchIds.add(Long.valueOf(m));
            } catch (NumberFormatException ignore) {
                // 跳过脏成员
            }
        }
        branchIds.sort(Long::compareTo);
        if (branchIds.isEmpty()) {
            return List.of();
        }

        List<String> keys = new ArrayList<>(branchIds.size() * stockShards);
        for (Long branchId : branchIds) {
            for (int i = 0; i < stockShards; i++) {
                keys.add(stockKey(productId, branchId, i));
            }
        }
        List<String> values = redis.opsForValue().multiGet(keys);

        List<BranchStockView> views = new ArrayList<>(branchIds.size());
        for (int b = 0; b < branchIds.size(); b++) {
            long sum = 0L;
            for (int i = 0; i < stockShards; i++) {
                String v = values == null ? null : values.get(b * stockShards + i);
                if (v != null) {
                    try {
                        sum += Long.parseLong(v);
                    } catch (NumberFormatException ignore) {
                        // 跳过异常值
                    }
                }
            }
            views.add(new BranchStockView(branchIds.get(b), sum));
        }
        return views;
    }

    /** 单个网点剩余额度（跨分片汇总） */
    public long remainingStock(Long productId, Long branchId) {
        List<String> keys = new ArrayList<>(stockShards);
        for (int i = 0; i < stockShards; i++) {
            keys.add(stockKey(productId, branchId, i));
        }
        List<String> values = redis.opsForValue().multiGet(keys);
        long sum = 0L;
        if (values != null) {
            for (String v : values) {
                if (v != null) {
                    try {
                        sum += Long.parseLong(v);
                    } catch (NumberFormatException ignore) {
                        // 跳过异常值
                    }
                }
            }
        }
        return sum;
    }

    /**
     * 原子扣减网点额度 + 限购（Lua，带分片 fallback）。
     *
     * <p>分片 fallback 只在<b>同一网点</b>的多个分片之间切换，语义仍是「这个网点还有没有额度」。
     * 起点按身份证哈希错开，避免所有请求都挤在 0 号分片。
     *
     * @return code=1 成功 / -1 该网点额度尽 / -2 已超过每人预约次数
     */
    public StockResult deductStock(Long productId, Long branchId, String idCard) {
        int startShard = Math.floorMod(idCard.hashCode(), stockShards);
        for (int i = 0; i < stockShards; i++) {
            int shard = (startShard + i) % stockShards;
            String stockKey = stockKey(productId, branchId, shard);
            String limitKey = limitKey(productId);
            Long result = redis.execute(deductStockScript, List.of(stockKey, limitKey),
                    idCard, String.valueOf(CommonConstants.LIMIT_PER_ID_CARD));
            if (result != null && result == 1L) {
                return new StockResult(1, shard);
            }
            if (result != null && result == -2L) {
                return new StockResult(-2, shard); // 超限约是全局的，直接返回
            }
            // -1 该分片额度尽，尝试下一分片
        }
        return new StockResult(-1, -1);
    }

    /** 补偿回滚：额度 +1、限购 -1（必须与 deductStock 落在同一个分片） */
    public void compensateStock(Long productId, Long branchId, String idCard, int shard) {
        redis.execute(compensateStockScript,
                List.of(stockKey(productId, branchId, shard), limitKey(productId)), idCard);
    }

    private String stockKey(Long productId, Long branchId, int shard) {
        return CommonConstants.KEY_STOCK + ":" + productId + ":" + branchId + ":" + shard;
    }

    private String limitKey(Long productId) {
        return CommonConstants.KEY_LIMIT + ":" + productId;
    }

    /** 扣减结果：code=1成功 / -1该网点额度尽 / -2超过每人预约次数，shard=实际扣减分片 */
    public record StockResult(int code, int shard) {
    }
}
