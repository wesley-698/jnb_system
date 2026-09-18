package com.psbc.coin.common.result;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一返回码。
 * 约定：0 成功；负数/非0 均为业务失败码。
 */
@Getter
@AllArgsConstructor
public enum ResultCode {

    SUCCESS(0, "成功"),
    SYSTEM_ERROR(500, "系统繁忙，请稍后再试"),
    PARAM_ERROR(400, "参数错误"),
    UNAUTHORIZED(401, "未认证或登录已过期"),
    FORBIDDEN(403, "无权限"),

    // 预约业务
    STOCK_EMPTY(1001, "该网点已约满"),
    LIMIT_EXCEEDED(1002, "超过限购数量"),
    DUPLICATE_SUBMIT(1003, "请勿重复提交"),
    NOT_QUALIFIED(1004, "未通过实名预填，无预约资格"),
    RESERVATION_NOT_FOUND(1005, "预约记录不存在"),
    RESERVATION_STATUS_ERROR(1006, "预约状态不允许该操作"),
    BRANCH_NOT_FOUND(1007, "网点不存在"),
    ACTIVITY_NOT_STARTED(1008, "活动尚未开始"),
    ACTIVITY_ENDED(1009, "活动已结束"),
    ALREADY_EXCHANGED(1010, "该预约已兑换，不可重复兑换"),
    PRODUCT_NOT_FOUND(1011, "产品不存在");

    private final int code;
    private final String message;
}
