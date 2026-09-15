package com.psbc.coin.reservation.controller;

import com.psbc.coin.common.result.Result;
import com.psbc.coin.reservation.config.DegradeManager;
import com.psbc.coin.reservation.dto.ReservationSubmitRequest;
import com.psbc.coin.reservation.service.ReservationService;
import com.psbc.coin.reservation.service.StockRebuildService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 预约接口。
 */
@RestController
@RequestMapping("/api/reservation")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;
    private final DegradeManager degradeManager;
    private final StockRebuildService stockRebuildService;

    /**
     * 提交预约意向（阶段2：资格校验 + 入队；Redis 故障时自动降级 DB 直扣）。
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
     * 运维：手动用 PG 数据重建 Redis 库存（Redis 恢复后也可由探活自动触发）。
     */
    @PostMapping("/rebuild/{productId}")
    public Result<Void> rebuild(@PathVariable Long productId) {
        stockRebuildService.rebuild(productId);
        return Result.success();
    }
}

