package com.psbc.coin.reservation.controller;

import com.psbc.coin.common.result.Result;
import com.psbc.coin.reservation.config.DegradeManager;
import com.psbc.coin.reservation.dto.BranchStockView;
import com.psbc.coin.reservation.dto.ReservationSubmitRequest;
import com.psbc.coin.reservation.dto.ReservationSubmitResponse;
import com.psbc.coin.reservation.service.ReservationService;
import com.psbc.coin.reservation.service.StockRebuildService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 预约接口（普通纪念币：先到先得、约满为止）。
 *
 * <p>提交即占用网点额度，并当场返回预约号，因此没有独立的「分配 / 抽签」接口。
 */
@RestController
@RequestMapping("/api/reservation")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;
    private final DegradeManager degradeManager;
    private final StockRebuildService stockRebuildService;

    /**
     * 提交预约。
     *
     * <p>成功即预约成功，返回 {@code orderNo}（预约号）与预约网点；
     * 失败返回明确原因（该网点已约满 / 已约过 / 不在预约期内 等），供前端引导用户换网点。
     */
    @PostMapping("/submit")
    public Result<ReservationSubmitResponse> submit(@Valid @RequestBody ReservationSubmitRequest request) {
        return reservationService.submit(request);
    }

    /**
     * 各网点剩余额度：供用户在提交前选择网点，避开已约满的网点。
     */
    @GetMapping("/stock/{productId}")
    public Result<List<BranchStockView>> stock(@PathVariable Long productId) {
        return Result.success(reservationService.branchStocks(productId));
    }

    /**
     * 运维：查看当前降级状态。
     */
    @GetMapping("/degrade/status")
    public Result<Map<String, Object>> degradeStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("dbDirectMode", degradeManager.isDbDirectMode());
        status.put("redisDown", degradeManager.isRedisDown());
        status.put("manualSwitch", degradeManager.isManualDbDirectEnabled());
        status.put("redisDownAt", degradeManager.getRedisDownAt());
        return Result.success(status);
    }

    /**
     * 运维：手动用 PG 数据重建 Redis 额度（Redis 恢复后也可由探活自动触发）。
     */
    @PostMapping("/rebuild/{productId}")
    public Result<Void> rebuild(@PathVariable Long productId) {
        stockRebuildService.rebuild(productId);
        return Result.success();
    }
}
