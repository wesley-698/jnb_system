package com.psbc.coin.reservation.service;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.psbc.coin.common.constant.CommonConstants;
import com.psbc.coin.common.exception.BizException;
import com.psbc.coin.common.result.Result;
import com.psbc.coin.common.result.ResultCode;
import com.psbc.coin.common.util.SnowflakeIdGenerator;
import com.psbc.coin.reservation.dto.ReservationSubmitRequest;
import com.psbc.coin.reservation.dto.ReservationSubmitResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ③ 降级模式：Redis 不可用时，直接在 PostgreSQL 主库上用单库事务扣减。
 *
 * <p>与主链路语义一致——同样是「提交即占用网点额度、当场返回预约号」，
 * 因此降级时用户拿到的结果含义不变，只是吞吐下降（靠强限流保护主库）。
 *
 * <p>防超卖靠：
 *   - 乐观锁 UPDATE ... WHERE remain > 0（affected=0 即该网点约满）
 *   - 唯一约束 (product_id, id_card)（冲突即已约过 → 重复预约无效）
 * 保护主库靠：
 *   - Sentinel 流控 dbDirectSubmit（强限流）
 *   - 单库事务保证「扣额度 + 写记录」原子，失败整体回滚（无需补偿）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DbDirectReservationService {

    private final JdbcTemplate jdbc;
    private final SnowflakeIdGenerator idGenerator;

    @Transactional(rollbackFor = Exception.class)
    @SentinelResource(value = "dbDirectSubmit", blockHandler = "blocked", fallback = "fallback")
    public Result<ReservationSubmitResponse> submitDirect(ReservationSubmitRequest req) {
        // 1) 限购兜底：同一身份证在同一产品已约过则拒绝（重复预约无效）
        Integer dup = jdbc.queryForObject(
                "SELECT count(*) FROM t_reservation WHERE product_id = ? AND id_card = ?",
                Integer.class, req.getProductId(), req.getIdCard());
        if (dup != null && dup > 0) {
            return Result.fail(ResultCode.LIMIT_EXCEEDED);
        }

        // 2) 乐观锁扣网点额度：affected=0 表示该网点已约满
        int updated = jdbc.update(
                "UPDATE t_branch_stock SET remain = remain - 1, version = version + 1 "
                        + "WHERE product_id = ? AND branch_id = ? AND remain > 0",
                req.getProductId(), req.getBranchId());
        if (updated != 1) {
            return Result.fail(ResultCode.STOCK_EMPTY);
        }

        // 3) 写预约记录；唯一约束冲突 → 抛异常触发事务回滚（刚扣的额度自动回补）
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

        // 4) 降级模式直接落库，不再经过 Kafka，因此没有 Redis 顺序号（sequenceNo 留空）
        Long remain = queryRemain(req.getProductId(), req.getBranchId());
        log.info("【降级直扣】成功: orderNo={}, productId={}, branchId={}, idCard={}",
                id, req.getProductId(), req.getBranchId(), req.getIdCard());
        return Result.success(new ReservationSubmitResponse(
                String.valueOf(id), null, req.getProductId(), req.getBranchId(),
                remain, CommonConstants.STATUS_RESERVED));
    }

    private Long queryRemain(Long productId, Long branchId) {
        try {
            return jdbc.queryForObject(
                    "SELECT remain FROM t_branch_stock WHERE product_id = ? AND branch_id = ?",
                    Long.class, productId, branchId);
        } catch (Exception e) {
            log.warn("查询网点剩余额度失败（不影响预约）: productId={}, branchId={}", productId, branchId);
            return null;
        }
    }

    /** Sentinel 流控/熔断触发 */
    public Result<ReservationSubmitResponse> blocked(ReservationSubmitRequest req, BlockException ex) {
        log.warn("【降级直扣】被限流/熔断拦截: {}", ex.getClass().getSimpleName());
        return Result.fail(ResultCode.SYSTEM_ERROR);
    }

    /** 业务/系统异常兜底 */
    public Result<ReservationSubmitResponse> fallback(ReservationSubmitRequest req, Throwable t) {
        if (t instanceof BizException be) {
            return Result.fail(be.getCode(), be.getMessage());
        }
        log.error("【降级直扣】异常", t);
        return Result.fail(ResultCode.SYSTEM_ERROR);
    }
}
