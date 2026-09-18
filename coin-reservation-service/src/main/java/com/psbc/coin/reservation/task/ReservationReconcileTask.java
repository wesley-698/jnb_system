package com.psbc.coin.reservation.task;

import com.psbc.coin.common.constant.CommonConstants;
import com.psbc.coin.common.dto.ReservationResultMessage;
import com.psbc.coin.common.util.JsonUtils;
import com.psbc.coin.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * ④ 定时对账：扫描「在途单」（resv:pending:{productId}），判断是否已落库。
 *   - 已落库 → 确认，移出在途集合
 *   - 超出等待窗口仍未落库 → 先查 PG 确认没落库，再幂等回补 Redis 额度 + 限购，然后移出
 *
 * <p>在途单是在 Redis 扣额成功后、投递 Kafka 之前写入的，因此它同时覆盖了
 * 「投递成功但消费失败 / 落库失败但补偿也失败 / 进程在扣额后崩溃」等场景，
 * 保证 Redis 扣减与实际落库最终一致（不丢额度、不多扣）。
 *
 * <p>回补统一走 {@link ReservationService#compensateOnce}：即使 Kafka 失败回调与本任务
 * 同时判定同一单失败，也只会真正回补一次，避免重复 +1 造成超发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationReconcileTask {

    private static final String PENDING_KEY_PATTERN = CommonConstants.KEY_PENDING + ":*";

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final ReservationService reservationService;

    @Value("${coin.reconcile.pending-timeout-seconds:120}")
    private long pendingTimeoutSeconds;

    /** SCAN 每批返回的 key 数量提示值（COUNT 是提示而非硬性上限） */
    @Value("${coin.reconcile.scan-count:500}")
    private long scanCount;

    @Scheduled(fixedDelayString = "${coin.reconcile.interval-ms:60000}", initialDelay = 30000)
    public void reconcile() {
        Set<String> pendingKeys = scanPendingKeys();
        if (pendingKeys.isEmpty()) {
            return;
        }

        long cutoff = System.currentTimeMillis() - pendingTimeoutSeconds * 1000L;
        int confirmed = 0;
        int compensatedCount = 0;

        for (String key : pendingKeys) {
            Set<String> stale;
            try {
                stale = redis.opsForZSet().rangeByScore(key, 0, cutoff);
            } catch (Exception e) {
                log.debug("对账跳过该 key（Redis 异常）: key={}, err={}", key, e.getMessage());
                continue;
            }
            if (stale == null || stale.isEmpty()) {
                continue;
            }
            for (String member : stale) {
                ReservationResultMessage msg;
                try {
                    msg = JsonUtils.fromJson(member, ReservationResultMessage.class);
                } catch (Exception e) {
                    redis.opsForZSet().remove(key, member);
                    continue;
                }

                // 先查 PG：已落库则确认
                Integer cnt = jdbc.queryForObject(
                        "SELECT count(*) FROM t_reservation WHERE order_no = ?",
                        Integer.class, msg.getOrderNo());
                if (cnt != null && cnt > 0) {
                    redis.opsForZSet().remove(key, member);
                    confirmed++;
                } else {
                    // 超时未落库 → 幂等回补 Redis 额度（网点额度 +1、限购 -1）
                    boolean compensated = reservationService.compensateOnce(
                            msg.getOrderNo(), msg.getProductId(), msg.getBranchId(),
                            msg.getIdCard(), msg.getShard());
                    redis.opsForZSet().remove(key, member);
                    if (compensated) {
                        compensatedCount++;
                        log.warn("【对账】超时未落库，已回补额度: orderNo={}", msg.getOrderNo());
                    } else {
                        log.warn("【对账】超时未落库，但回补未执行（已回补过或回补失败）: orderNo={}", msg.getOrderNo());
                    }
                }
            }
        }

        if (confirmed > 0 || compensatedCount > 0) {
            log.info("【对账】完成: 确认={}, 回补={}", confirmed, compensatedCount);
        }
    }

    /**
     * 用 SCAN 游标分批遍历在途单 key，替代阻塞式的 {@code KEYS}。
     *
     * <p>为什么必须换掉 {@code KEYS}：Redis 单线程执行命令，{@code KEYS} 会一次性
     * 遍历整个 key space 并在此期间阻塞所有其它命令。本工程光网点额度分片 key
     * （产品 × 网点 × 10 分片）就可能上万，再叠加活动期的幂等键，
     * 一次 {@code KEYS resv:pending:*} 足以让整个集群卡顿。
     * SCAN 只按 COUNT 提示值分多批返回，期间可正常处理其它命令。
     *
     * <p>两个 SCAN 的固有特性已在本方法内处理：
     * <ol>
     *   <li><b>可能返回重复 key</b>（rehash 期间）→ 用 {@link HashSet} 去重，
     *       否则同一在途单会被处理两次（回补有幂等标记兜底，但会白白多查一次 PG）；</li>
     *   <li><b>中途失败可能只扫到一部分</b>→ 已扫到的 key 仍然处理，剩余的下轮再扫，
     *       不会因为一次网络抖动而丢失对账。</li>
     * </ol>
     */
    private Set<String> scanPendingKeys() {
        Set<String> keys = new HashSet<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match(PENDING_KEY_PATTERN)
                .count(scanCount)
                .build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        } catch (Exception e) {
            // 游标中途断开：已收集的 key 仍然有效，本轮先处理这些，最后一轮补扫
            log.warn("SCAN 在途单 key 中断，本轮先处理已扫到的 {} 个 key: {}", keys.size(), e.getMessage());
        }
        return keys;
    }
}
