package com.psbc.coin.reservation.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 抽签/分配调度器。
 * 生产环境：在活动 draw_time 到达后，扫描「待分配产品」触发 draw()。
 * 此处仅保留调度骨架，实际触发逻辑接入 product-service 的活动配置。
 */
@Slf4j
@Component
public class DrawScheduler {

    /**
     * 每 30 秒扫描一次待分配活动（占位，实际从 product-service / 本地缓存拉取）。
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    public void scanDrawableActivities() {
        // TODO: 接入活动配置后，遍历 draw_time 已到且未分配的 productId，调用 reservationService.draw(productId, drawMode)
        log.debug("抽签调度扫描（占位）");
    }
}
