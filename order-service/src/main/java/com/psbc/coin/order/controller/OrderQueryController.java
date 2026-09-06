package com.psbc.coin.order.controller;

import com.psbc.coin.common.result.Result;
import com.psbc.coin.common.result.ResultCode;
import com.psbc.coin.order.entity.Reservation;
import com.psbc.coin.order.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 预约明细查询接口（读多走从库）。
 */
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderQueryController {

    private final ReservationRepository reservationRepository;

    /** 按身份证查询预约列表 */
    @GetMapping("/list")
    public Result<List<Reservation>> list(@RequestParam String idCard) {
        return Result.success(reservationRepository.findByIdCardOrderByCreateTimeDesc(idCard));
    }

    /** 查询单条预约详情 */
    @GetMapping("/detail/{orderNo}")
    public Result<Reservation> detail(@PathVariable String orderNo) {
        return reservationRepository.findByOrderNo(orderNo)
                .map(Result::success)
                .orElseGet(() -> Result.fail(ResultCode.RESERVATION_NOT_FOUND));
    }
}
