package com.psbc.coin.reservation.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 预约提交结果（先到先得：提交那一刻即已占用网点额度，当场返回预约结果）。
 *
 * <p>与真实业务一致：用户拿到 {@code orderNo}（预约号）即代表预约成功，
 * 可凭此在兑换期到 {@code branchId} 对应网点兑换。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReservationSubmitResponse {

    /** 预约号（对外唯一凭证，兑换时出示） */
    private String orderNo;

    /** 预约顺序号（该产品第 N 位预约成功者；降级直扣模式下可能为空） */
    private Long sequenceNo;

    private Long productId;

    /** 预约网点 */
    private Long branchId;

    /** 该网点扣减后的剩余额度（便于前端提示"仅剩 N 份"） */
    private Long branchRemain;

    /** 预约状态：1 已预约 */
    private Integer status;
}
