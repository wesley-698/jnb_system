package com.psbc.coin.reservation.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ③ 降级管理器：决定当前走「Redis 主模式」还是「DB 直扣降级模式」。
 *
 * 两条触发路径：
 *   1. 手动：Nacos 配置 coin.degrade.db-direct-enabled=true（@RefreshScope 动态刷新生效）
 *   2. 自动：Redis 操作抛异常时 markRedisDown()，由 RedisHealthProbe 探活恢复
 */
@Slf4j
@Component
@RefreshScope
public class DegradeManager {

    /** 手动降级开关（Nacos 动态刷新） */
    @Value("${coin.degrade.db-direct-enabled:false}")
    private boolean manualDbDirectEnabled;

    private final AtomicBoolean redisDown = new AtomicBoolean(false);
    private volatile long redisDownAt = 0L;

    /** 是否走 DB 直扣降级模式 */
    public boolean isDbDirectMode() {
        return manualDbDirectEnabled || redisDown.get();
    }

    /** 标记 Redis 不可用（触发自动降级） */
    public void markRedisDown() {
        if (redisDown.compareAndSet(false, true)) {
            redisDownAt = System.currentTimeMillis();
            log.error("【降级】检测到 Redis 不可用，切换到 DB 直扣模式（强限流保护主库）");
        }
    }

    /** 标记 Redis 已恢复 */
    public void markRedisUp() {
        if (redisDown.compareAndSet(true, false)) {
            log.warn("【恢复】Redis 已恢复，切回 Redis 主模式");
        }
    }

    public boolean isRedisDown() {
        return redisDown.get();
    }

    public boolean isManualDbDirectEnabled() {
        return manualDbDirectEnabled;
    }

    public long getRedisDownAt() {
        return redisDownAt;
    }
}
