package com.psbc.coin.reservation.task;

import com.psbc.coin.common.constant.CommonConstants;
import com.psbc.coin.common.dto.ReservationResultMessage;
import com.psbc.coin.common.util.JsonUtils;
import com.psbc.coin.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * ④ 定时对账：扫描「待确认中签单」（resv:pending:{productId}），判断是否已落库。
 *   - 已落库 → 确认，移出待确认集合
 *   - 超时仍未落库 → 先查 PG（已查）再补偿：回补 Redis 库存 + 限购，然后移出
 *
 * 这是「投递成功但消费失败 / 落库失败但补偿也失败」等场景的最终兜底，
 * 保证 Redis 扣减与实际落库最终一致（不丢额度、不多扣）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationReconcileTask {

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final ReservationService reservationService;

    @Value("${coin.reconcile.pending-timeout-seconds:120}")
    private long pendingTimeoutSeconds;

    @Scheduled(fixedDelayString = "${coin.reconcile.interval-ms:60000}", initialDelay = 30000)
    public void reconcile() {
        Set<String> pendingKeys;
        try {
            pendingKeys = redis.keys(CommonConstants.KEY_PENDING + ":*");
        } catch (Exception e) {
            log.debug("对账跳过（Redis 不可用）: {}", e.getMessage());
            return;
        }
        if (pendingKeys == null || pendingKeys.isEmpty()) {
            return;
        }

        long cutoff = System.currentTimeMillis() - pendingTimeoutSeconds * 1000L;
        int confirmed = 0;
        int compensated = 0;

        for (String key : pendingKeys) {
            Set<String> stale = redis.opsForZSet().rangeByScore(key, 0, cutoff);
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
                    // 超时未落库 → 补偿回补 Redis（库存 +1、限购 -1）
                    reservationService.compensateStock(
                            msg.getProductId(), msg.getBranchId(), msg.getIdCard(), msg.getShard());
                    redis.opsForZSet().remove(key, member);
                    compensated++;
                    log.warn("【对账】超时未落库，已补偿回补: orderNo={}", msg.getOrderNo());
                }
            }
        }

        if (confirmed > 0 || compensated > 0) {
            log.info("【对账】完成: 确认={}, 补偿={}", confirmed, compensated);
        }
    }
}
