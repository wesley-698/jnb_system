package com.psbc.coin.reservation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 预约提交请求（先到先得：提交即占用所选网点额度）。
 */
@Data
public class ReservationSubmitRequest {

    @NotNull
    private Long productId;

    /** 用户所选预约网点（额度按网点分配，选错网点会在提交时得到「该网点已约满」） */
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
