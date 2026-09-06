package com.psbc.coin.order.service;

import com.psbc.coin.common.constant.CommonConstants;
import com.psbc.coin.common.dto.ReservationResultMessage;
import com.psbc.coin.common.exception.BizException;
import com.psbc.coin.common.result.ResultCode;
import com.psbc.coin.common.util.SnowflakeIdGenerator;
import com.psbc.coin.order.entity.Reservation;
import com.psbc.coin.order.repository.BranchStockRepository;
import com.psbc.coin.order.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 落库事务：扣网点库存 + 写预约记录，同一事务，任一失败整体回滚。
 */
@Service
@RequiredArgsConstructor
public class ReservationPersistService {

    private final ReservationRepository reservationRepository;
    private final BranchStockRepository branchStockRepository;
    private final SnowflakeIdGenerator idGenerator;

    @Transactional(rollbackFor = Exception.class)
    public void confirm(ReservationResultMessage msg) {
        // 1. 扣网点库存（乐观条件 remain>0，0 行 → 该网点已满，抛异常回滚）
        int updated = branchStockRepository.deduct(msg.getProductId(), msg.getBranchId());
        if (updated != 1) {
            throw new BizException(ResultCode.STOCK_EMPTY);
        }

        // 2. 写预约记录（唯一约束 (product_id, id_card) 兜底防超限购）
        Reservation r = new Reservation();
        r.setId(idGenerator.nextId());
        r.setOrderNo(msg.getOrderNo());
        r.setProductId(msg.getProductId());
        r.setBranchId(msg.getBranchId());
        r.setUserId(msg.getUserId());
        r.setIdCard(msg.getIdCard());
        r.setSequenceNo(msg.getSequenceNo());
        r.setStatus(CommonConstants.STATUS_RESERVED);
        reservationRepository.save(r);
        // 方法正常返回 → COMMIT；抛异常 → 两条操作一起 ROLLBACK
    }
}
