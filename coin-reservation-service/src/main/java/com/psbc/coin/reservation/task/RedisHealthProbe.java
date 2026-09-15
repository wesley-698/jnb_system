package com.psbc.coin.reservation.task;

import com.psbc.coin.reservation.config.DegradeManager;
import com.psbc.coin.reservation.service.StockRebuildService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ①+④ Redis 健康探测：
 *   - 仅在降级状态下周期性探活（避免健康时无谓开销）
 *   - 探活成功 → 切回 Redis 主模式，并用 PG 重建 Redis 库存
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisHealthProbe {

    private final StringRedisTemplate redis;
    private final DegradeManager degradeManager;
    private final StockRebuildService stockRebuildService;

    @Scheduled(fixedDelay = 5000)
    public void probe() {
        if (!degradeManager.isRedisDown()) {
            return;
        }
        try {
            // 轻量探活：任意一次命令成功即认为恢复
            redis.hasKey("health:probe");
            degradeManager.markRedisUp();
            stockRebuildService.rebuildAll();
        } catch (Exception e) {
            log.debug("Redis 仍未恢复: {}", e.getMessage());
        }
    }
}
