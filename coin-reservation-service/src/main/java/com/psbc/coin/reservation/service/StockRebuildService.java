package com.psbc.coin.reservation.service;

import com.psbc.coin.common.constant.CommonConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * ④ 恢复：用 PostgreSQL（权威数据源）重建 Redis 库存分片。
 *
 * 触发时机：
 *   - Redis 故障恢复后（RedisHealthProbe 自动调用）
 *   - 运维手动调用 /api/reservation/rebuild/{productId}
 *
 * 注意：以「PG remain」为准重建。若存在「Redis 已扣但尚未落库」的在途订单，
 * 重建会把在这部分额度加回去，可能造成 Redis 短暂多算；由 PG 的乐观锁 +
 * 唯一约束作为最终兜底（最坏情况是个别请求在落库阶段被拒，不会超卖）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockRebuildService {

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    @Value("${coin.stock-shards:10}")
    private int stockShards;

    /** 重建全部产品的库存分片 */
    public void rebuildAll() {
        List<Long> productIds = jdbc.queryForList(
                "SELECT DISTINCT product_id FROM t_branch_stock", Long.class);
        for (Long pid : productIds) {
            rebuild(pid);
        }
    }

    /** 重建指定产品：从 PG 的 t_branch_stock.remain 拆分片写回 Redis */
    public void rebuild(Long productId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT branch_id, remain FROM t_branch_stock WHERE product_id = ?", productId);
        // 同步刷新可预约网点清单，保证余量查询接口在重建后依然可用
        String branchesKey = CommonConstants.KEY_BRANCHES + ":" + productId;
        redis.delete(branchesKey);
        for (Map<String, Object> row : rows) {
            long branchId = ((Number) row.get("branch_id")).longValue();
            long remain = ((Number) row.get("remain")).longValue();
            writeShards(productId, branchId, remain);
            redis.opsForSet().add(branchesKey, String.valueOf(branchId));
        }
        log.info("【重建】Redis 额度已按 PG 重建: productId={}, 网点数={}", productId, rows.size());
    }

    private void writeShards(Long productId, long branchId, long remain) {
        long perShard = remain / stockShards;
        long remainder = remain % stockShards;
        for (int i = 0; i < stockShards; i++) {
            long shardStock = perShard + (i == stockShards - 1 ? remainder : 0);
            redis.opsForValue().set(
                    CommonConstants.KEY_STOCK + ":" + productId + ":" + branchId + ":" + i,
                    String.valueOf(shardStock));
        }
    }
}
