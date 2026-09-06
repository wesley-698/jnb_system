package com.psbc.coin.exchange.controller;

import com.psbc.coin.common.constant.CommonConstants;
import com.psbc.coin.common.exception.BizException;
import com.psbc.coin.common.result.Result;
import com.psbc.coin.common.result.ResultCode;
import com.psbc.coin.exchange.entity.Exchange;
import com.psbc.coin.exchange.entity.Reservation;
import com.psbc.coin.exchange.repository.ExchangeRepository;
import com.psbc.coin.exchange.repository.ReservationRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * 线下柜面兑换登记接口。
 */
@RestController
@RequestMapping("/api/exchange")
@RequiredArgsConstructor
public class ExchangeController {

    private final ReservationRepository reservationRepository;
    private final ExchangeRepository exchangeRepository;

    /**
     * 兑换登记：校验预约（存在/状态/网点/身份证）→ 标记已兑换 + 写流水。
     */
    @PostMapping("/register")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> register(@RequestBody ExchangeRequest request) {
        Reservation r = reservationRepository.findByOrderNo(request.getOrderNo())
                .orElseThrow(() -> new BizException(ResultCode.RESERVATION_NOT_FOUND));

        if (r.getStatus() != CommonConstants.STATUS_RESERVED) {
            throw new BizException(ResultCode.ALREADY_EXCHANGED);
        }
        if (!r.getIdCard().equals(request.getIdCard())) {
            throw new BizException(ResultCode.PARAM_ERROR);
        }
        if (!r.getBranchId().equals(request.getBranchId())) {
            throw new BizException(ResultCode.PARAM_ERROR);
        }

        // 标记预约已兑换
        r.setStatus(CommonConstants.STATUS_EXCHANGED);
        reservationRepository.save(r);

        // 写兑换流水（order_no 唯一约束防重复兑换）
        Exchange e = new Exchange();
        e.setOrderNo(r.getOrderNo());
        e.setProductId(r.getProductId());
        e.setBranchId(r.getBranchId());
        e.setIdCard(r.getIdCard());
        e.setOperatorId(request.getOperatorId());
        exchangeRepository.save(e);
        return Result.success();
    }

    @Data
    public static class ExchangeRequest {
        private String orderNo;
        private String idCard;
        private Long branchId;
        private Long operatorId;
    }
}
