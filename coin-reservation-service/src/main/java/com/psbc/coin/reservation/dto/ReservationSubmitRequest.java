package com.psbc.coin.reservation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 预约提交请求（阶段2：资格校验 + 入队）。
 */
@Data
public class ReservationSubmitRequest {

    @NotNull
    private Long productId;

    @NotNull
    private Long branchId;

    @NotNull
    private Long userId;

    @NotBlank
    private String idCard;

    /** 一次性幂等 Token（防重复提交） */
    @NotBlank
    private String token;
}
