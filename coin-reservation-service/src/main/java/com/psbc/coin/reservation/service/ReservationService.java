package com.psbc.coin.reservation.service;

import com.psbc.coin.common.constant.CommonConstants;
import com.psbc.coin.common.dto.ReservationResultMessage;
import com.psbc.coin.common.result.Result;
import com.psbc.coin.common.result.ResultCode;
import com.psbc.coin.common.util.JsonUtils;
import com.psbc.coin.common.util.SnowflakeIdGenerator;
import com.psbc.coin.reservation.dto.ReservationSubmitRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 预约核心服务。
 * 峰值主链路只依赖 Redis + Kafka，不碰数据库。
 */
@Slf4j
@Service
public class ReservationService {

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> deductStockScript;
    private final DefaultRedisScript<Long> compensateStockScript;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final SnowflakeIdGenerator idGenerator;

    @Value("${coin.stock-shards:10}")
    private int stockShards;

    @Value("${coin.queue-shards:10}")
    private int queueShards;

    public ReservationService(StringRedisTemplate redis,
                              DefaultRedisScript<Long> deductStockScript,
                              DefaultRedisScript<Long> compensateStockScript,
                              KafkaTemplate<String, String> kafkaTemplate,
                              SnowflakeIdGenerator idGenerator) {
        this.redis = redis;
        this.deductStockScript = deductStockScript;
        this.compensateStockScript = compensateStockScript;
        this.kafkaTemplate = kafkaTemplate;
        this.idGenerator = idGenerator;
    }

    /**
     * 阶段2：资格校验 + 入队（幂等、不扣库存、不写 DB）。
     */
    public Result<Void> submit(ReservationSubmitRequest req) {
        // 1. 幂等去重
        String idemKey = CommonConstants.KEY_IDEMPOTENT + ":" + req.getToken();
        Boolean set = redis.opsForValue().setIfAbsent(idemKey, "1", Duration.ofMinutes(10));
        if (!Boolean.TRUE.equals(set)) {
            return Result.fail(ResultCode.DUPLICATE_SUBMIT);
        }

        // 2. 实名资格校验
        String qualKey = CommonConstants.KEY_QUALIFICATION + ":" + req.getUserId();
        if (!Boolean.TRUE.equals(redis.hasKey(qualKey))) {
            return Result.fail(ResultCode.NOT_QUALIFIED);
        }

        // 3. 入队/入池（TODO: drawMode 应从产品配置读取，此处默认排队模式）
        enqueue(req, CommonConstants.DRAW_MODE_QUEUE);
        return Result.success();
    }

    /**
     * 阶段3：抽签/排队分配——弹出成员 → 原子扣库存 → 发 Kafka。
     */
    public void draw(Long productId, int drawMode) {
        for (int shard = 0; shard < queueShards; shard++) {
            drawFromShard(productId, shard, drawMode);
        }
        log.info("分配完成: productId={}, drawMode={}", productId, drawMode);
    }

    private void enqueue(ReservationSubmitRequest req, int drawMode) {
        int shard = Math.floorMod(req.getUserId().hashCode(), queueShards);
        String member = req.getUserId() + ":" + req.getBranchId() + ":" + req.getIdCard();
        if (drawMode == CommonConstants.DRAW_MODE_LOTTERY) {
            redis.opsForSet().add(poolKey(req.getProductId(), shard), member);
        } else {
            long seq = nextSequence(req.getProductId());
            redis.opsForZSet().add(queueKey(req.getProductId(), shard), member, seq);
        }
    }

    private void drawFromShard(Long productId, int shard, int drawMode) {
        String key = drawMode == CommonConstants.DRAW_MODE_LOTTERY
                ? poolKey(productId, shard) : queueKey(productId, shard);
        while (true) {
            String member = pop(key, drawMode);
            if (member == null) {
                break;
            }
            String[] parts = member.split(":");
            if (parts.length < 3) {
                continue;
            }
            Long userId = Long.valueOf(parts[0]);
            Long branchId = Long.valueOf(parts[1]);
            String idCard = parts[2];

            StockResult sr = deductStock(productId, branchId, idCard);
            if (sr.code() == 1) {
                sendResult(productId, branchId, userId, idCard, sr.shard());
            } else if (sr.code() == -2) {
                log.warn("用户超限购，跳过: idCard={}", idCard);
            } else {
                // 该网点库存尽，跳过当前成员（网点已满）
                log.debug("网点库存尽: productId={}, branchId={}", productId, branchId);
            }
        }
    }

    private String pop(String key, int drawMode) {
        if (drawMode == CommonConstants.DRAW_MODE_LOTTERY) {
            return redis.opsForSet().pop(key);
        }
        Set<String> first = redis.opsForZSet().range(key, 0, 0);
        if (first == null || first.isEmpty()) {
            return null;
        }
        String member = first.iterator().next();
        redis.opsForZSet().remove(key, member);
        return member;
    }

    /**
     * 原子扣减网点库存 + 限购（Lua，带分片 fallback）。
     */
    public StockResult deductStock(Long productId, Long branchId, String idCard) {
        int startShard = Math.floorMod(idCard.hashCode(), stockShards);
        for (int i = 0; i < stockShards; i++) {
            int shard = (startShard + i) % stockShards;
            String stockKey = stockKey(productId, branchId, shard);
            String limitKey = limitKey(productId);
            Long result = redis.execute(deductStockScript, List.of(stockKey, limitKey), idCard, "1");
            if (result != null && result == 1L) {
                return new StockResult(1, shard);
            }
            if (result != null && result == -2L) {
                return new StockResult(-2, shard); // 超限购是全局的，直接返回
            }
            // -1 库存尽，尝试下一分片
        }
        return new StockResult(-1, -1);
    }

    /**
     * 补偿回滚：库存 +1、限购 -1。
     */
    public void compensateStock(Long productId, Long branchId, String idCard, int shard) {
        redis.execute(compensateStockScript,
                List.of(stockKey(productId, branchId, shard), limitKey(productId)), idCard);
    }

    /**
     * 异步发 Kafka 中签消息（非阻塞，契合高并发）；投递失败则补偿回滚 Redis。
     * 需同时处理两类失败：send() 同步抛异常（如 max.block.ms 超时）与 future 异步失败。
     */
    private void sendResult(Long productId, Long branchId, Long userId, String idCard, int shard) {
        String orderNo = String.valueOf(idGenerator.nextId());
        ReservationResultMessage msg = new ReservationResultMessage(
                UUID.randomUUID().toString(), orderNo, productId, branchId, userId, idCard, null,
                shard, System.currentTimeMillis());
        try {
            kafkaTemplate.send(CommonConstants.TOPIC_RESV_RESULT, orderNo, JsonUtils.toJson(msg))
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            // 投递成功：记录待确认单（供对账扫描）
                            redis.opsForZSet().add(CommonConstants.KEY_PENDING + ":" + productId, orderNo,
                                    System.currentTimeMillis());
                        } else {
                            // future 异步失败：补偿回滚库存
                            log.error("Kafka 投递失败，补偿回滚库存: orderNo={}", orderNo, ex);
                            compensateStock(productId, branchId, idCard, shard);
                        }
                    });
        } catch (Exception e) {
            // send() 同步抛异常（如 max.block.ms 超时）：补偿回滚库存
            log.error("Kafka send 同步失败，补偿回滚库存: orderNo={}", orderNo, e);
            compensateStock(productId, branchId, idCard, shard);
        }
    }

    private long nextSequence(Long productId) {
        return redis.opsForValue().increment(CommonConstants.KEY_SEQ + ":" + productId);
    }

    private String stockKey(Long productId, Long branchId, int shard) {
        return CommonConstants.KEY_STOCK + ":" + productId + ":" + branchId + ":" + shard;
    }

    private String limitKey(Long productId) {
        return CommonConstants.KEY_LIMIT + ":" + productId;
    }

    private String queueKey(Long productId, int shard) {
        return CommonConstants.KEY_QUEUE + ":" + productId + ":" + shard;
    }

    private String poolKey(Long productId, int shard) {
        return CommonConstants.KEY_POOL + ":" + productId + ":" + shard;
    }

    /** 扣减结果：code=1成功 / -1库存尽 / -2超限购，shard=实际扣减分片 */
    public record StockResult(int code, int shard) {
    }
}
