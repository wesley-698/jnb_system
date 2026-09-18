package com.psbc.coin.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kafka 预约结果消息（预约服务 → 订单服务）：额度已在 Redis 扣减，此处用于异步落库。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReservationResultMessage {

    private String messageId;
    private String orderNo;
    private Long productId;
    private Long branchId;
    private Long userId;
    private String idCard;
    private Long sequenceNo;
    /** 扣减的库存分片号，用于失败补偿精确回补 */
    private int shard;
    private long timestamp;
}
