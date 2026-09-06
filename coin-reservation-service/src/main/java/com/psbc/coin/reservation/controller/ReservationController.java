package com.psbc.coin.reservation.controller;

import com.psbc.coin.common.result.Result;
import com.psbc.coin.reservation.dto.ReservationSubmitRequest;
import com.psbc.coin.reservation.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 预约接口。
 */
@RestController
@RequestMapping("/api/reservation")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    /**
     * 提交预约意向（阶段2：资格校验 + 入队）。
     */
    @PostMapping("/submit")
    public Result<Void> submit(@Valid @RequestBody ReservationSubmitRequest request) {
        return reservationService.submit(request);
    }

    /**
     * 手动触发抽签/分配（阶段3，正常由调度器在截止后自动执行）。
     */
    @PostMapping("/draw/{productId}")
    public Result<Void> draw(@PathVariable Long productId,
                             @RequestParam(defaultValue = "1") int drawMode) {
        reservationService.draw(productId, drawMode);
        return Result.success();
    }
}
