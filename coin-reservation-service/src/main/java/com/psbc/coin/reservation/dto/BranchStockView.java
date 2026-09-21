package com.psbc.coin.reservation.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 网点剩余额度视图（用于前端展示各网点余量，支撑「该网点已约满 → 换网点」）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BranchStockView {

    private Long branchId;

    /** 剩余可预约额度 */
    private Long remain;
}
