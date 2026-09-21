package com.psbc.coin.reservation.config;

import com.psbc.coin.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 活动时间窗本地缓存（开闸 / 截止）。
 *
 * <p>真实业务里「先到先得」的前提是**开闸时刻**（如工行 22:00 开闸，预约期内约满为止），
 * 因此提交前必须校验当前是否处于预约期内。为了不在百万并发主链路上查数据库，
 * 这里把 {@code t_product} 的开闸/截止时间定时拉进本地内存，提交时只读内存。
 *
 * <p>可用性取舍：DB 刷新失败时沿用上一份缓存；若**从未成功加载过**（如服务刚启动、
 * DB 短暂不可用），则放行并打警告——宁可放行也不因为缓存没热起来就把全量用户拒之门外。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityWindowCache {

    private final JdbcTemplate jdbc;

    private record Window(LocalDateTime submitStart, LocalDateTime submitEnd) {
    }

    private volatile Map<Long, Window> windows = Map.of();

    /** 是否成功加载过至少一次；false 时校验放行（fail-open） */
    private volatile boolean loaded = false;

    @Scheduled(fixedDelayString = "${coin.activity.refresh-interval-ms:5000}", initialDelay = 0)
    public void refresh() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, submit_start, submit_end FROM t_product");
            Map<Long, Window> next = new HashMap<>(Math.max(16, rows.size() * 2));
            for (Map<String, Object> row : rows) {
                Object id = row.get("id");
                if (id == null) {
                    continue;
                }
                next.put(((Number) id).longValue(), new Window(
                        toLocalDateTime(row.get("submit_start")),
                        toLocalDateTime(row.get("submit_end"))));
            }
            this.windows = Map.copyOf(next);
            this.loaded = true;
        } catch (Exception e) {
            // 沿用上一份缓存，保证主链路不受 DB 抖动影响
            log.warn("活动时间窗缓存刷新失败，沿用上一份缓存(loaded={}): {}", loaded, e.getMessage());
        }
    }

    /**
     * 校验当前是否可提交预约。
     *
     * @return {@code null} 表示通过；否则为对应的业务失败码
     */
    public ResultCode check(Long productId, LocalDateTime now) {
        if (!loaded) {
            return null;
        }
        Window w = windows.get(productId);
        if (w == null) {
            return ResultCode.PRODUCT_NOT_FOUND;
        }
        if (w.submitStart() != null && now.isBefore(w.submitStart())) {
            return ResultCode.ACTIVITY_NOT_STARTED;
        }
        if (w.submitEnd() != null && !now.isBefore(w.submitEnd())) {
            return ResultCode.ACTIVITY_ENDED;
        }
        return null;
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt;
        }
        return null;
    }
}
