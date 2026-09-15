package com.psbc.coin.reservation.service;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.psbc.coin.common.constant.CommonConstants;
import com.psbc.coin.common.exception.BizException;
import com.psbc.coin.common.result.Result;
import com.psbc.coin.common.result.ResultCode;
import com.psbc.coin.common.util.SnowflakeIdGenerator;
import com.psbc.coin.reservation.dto.ReservationSubmitRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ③ 降级模式：Redis 不可用时，直接在 PostgreSQL 主库上用单库事务扣减。
 *
 * 防超卖靠：
 *   - 乐观锁 UPDATE ... WHERE remain > 0（affected=0 即该网点约满）
 *   - 唯一约束 (product_id, id_card)（冲突即已约过）
 * 保护主库靠：
 *   - Sentinel 流控 dbDirectSubmit（强限流）
 *   - 单库事务保证「扣库存 + 写记录」原子，失败整体回滚（无需补偿）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DbDirectReservationService {

    private final JdbcTemplate jdbc;
    private final SnowflakeIdGenerator idGenerator;

    @Transactional(rollbackFor = Exception.class)
    @SentinelResource(value = "dbDirectSubmit", blockHandler = "blocked", fallback = "fallback")
    public Result<Void> submitDirect(ReservationSubmitRequest req) {
        // 1) 限购兜底：同一身份证在同一产品已约过则拒绝
        Integer dup = jdbc.queryForObject(
                "SELECT count(*) FROM t_reservation WHERE product_id = ? AND id_card = ?",
                Integer.class, req.getProductId(), req.getIdCard());
        if (dup != null && dup > 0) {
            return Result.fail(ResultCode.LIMIT_EXCEEDED);
        }

        // 2) 乐观锁扣网点库存：affected=0 表示该网点已约满
        int updated = jdbc.update(
                "UPDATE t_branch_stock SET remain = remain - 1, version = version + 1 "
                        + "WHERE product_id = ? AND branch_id = ? AND remain > 0",
                req.getProductId(), req.getBranchId());
        if (updated != 1) {
            return Result.fail(ResultCode.STOCK_EMPTY);
        }

        // 3) 写预约记录；唯一约束冲突 → 抛异常触发事务回滚（刚扣的库存自动回补）
        long id = idGenerator.nextId();
        try {
            jdbc.update("INSERT INTO t_reservation "
                            + "(id, order_no, product_id, branch_id, user_id, id_card, status, create_time, update_time) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())",
                    id, String.valueOf(id), req.getProductId(), req.getBranchId(),
                    req.getUserId(), req.getIdCard(), CommonConstants.STATUS_RESERVED);
        } catch (DuplicateKeyException e) {
            throw new BizException(ResultCode.LIMIT_EXCEEDED);
        }

        log.info("【降级直扣】成功: orderNo={}, productId={}, branchId={}, idCard={}",
                id, req.getProductId(), req.getBranchId(), req.getIdCard());
        return Result.success();
    }

    /** Sentinel 流控/熔断触发 */
    public Result<Void> blocked(ReservationSubmitRequest req, BlockException ex) {
        log.warn("【降级直扣】被限流/熔断拦截: {}", ex.getClass().getSimpleName());
        return Result.fail(ResultCode.SYSTEM_ERROR);
    }

    /** 业务/系统异常兜底 */
    public Result<Void> fallback(ReservationSubmitRequest req, Throwable t) {
        if (t instanceof BizException be) {
            return Result.fail(be.getCode(), be.getMessage());
        }
        log.error("【降级直扣】异常", t);
        return Result.fail(ResultCode.SYSTEM_ERROR);
    }
}
