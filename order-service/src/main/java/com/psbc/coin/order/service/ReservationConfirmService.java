package com.psbc.coin.order.service;

import com.psbc.coin.common.constant.CommonConstants;
import com.psbc.coin.common.dto.ReservationResultMessage;
import com.psbc.coin.common.util.JsonUtils;
import com.psbc.coin.order.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Kafka 中签消息消费者：幂等校验 → 落库事务 → 失败补偿回补 Redis。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationConfirmService {

    private final ReservationRepository reservationRepository;
    private final ReservationPersistService persistService;
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> compensateStockScript;

    @KafkaListener(topics = CommonConstants.TOPIC_RESV_RESULT, groupId = "order-consumer-group")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        ReservationResultMessage msg = JsonUtils.fromJson(record.value(), ReservationResultMessage.class);
        log.info("消费中签消息: orderNo={}, idCard={}, branchId={}", msg.getOrderNo(), msg.getIdCard(), msg.getBranchId());
        try {
            // 幂等：已处理过则直接确认
            if (reservationRepository.existsByOrderNo(msg.getOrderNo())) {
                ack.acknowledge();
                return;
            }
            persistService.confirm(msg);
            ack.acknowledge();
            log.info("落库成功: orderNo={}", msg.getOrderNo());
        } catch (Exception e) {
            // 落库失败（事务已回滚）→ 补偿回补 Redis（先查后补 + 幂等由对账兜底）
            log.error("落库失败，触发 Redis 补偿: orderNo={}", msg.getOrderNo(), e);
            compensateRedis(msg);
            // 简化处理：直接 ack，避免无限重试；生产应进入死信队列 + 定时对账
            ack.acknowledge();
        }
    }

    /**
     * 补偿回补：网点库存 +1、限购 -1（精确定位到扣减分片）。
     */
    private void compensateRedis(ReservationResultMessage msg) {
        String stockKey = CommonConstants.KEY_STOCK + ":" + msg.getProductId() + ":"
                + msg.getBranchId() + ":" + msg.getShard();
        String limitKey = CommonConstants.KEY_LIMIT + ":" + msg.getProductId();
        redis.execute(compensateStockScript, List.of(stockKey, limitKey), msg.getIdCard());
    }
}
