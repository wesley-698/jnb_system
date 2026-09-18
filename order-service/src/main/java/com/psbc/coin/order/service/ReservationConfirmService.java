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

import java.time.Duration;
import java.util.List;

/**
 * Kafka 预约结果消费者：幂等校验 → 落库事务 → 失败幂等回补 Redis 额度。
 *
 * <p>落库成功（或已存在）时顺带**清除预约服务的在途单**（`resv:pending:{productId}`），
 * 这样正常链路下在途单存活时间只有毫秒级，对账任务只需处理真正卡住的单。
 *
 * <p>回补必须与预约服务的 {@code compensateOnce} 共用同一个去重标记
 * （`resv:compensated:{orderNo}`）：否则「消费端回补」与「对账任务回补」会各补一次，
 * 额度被多加两份 → 超发。
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
        log.info("消费预约消息: orderNo={}, idCard={}, branchId={}", msg.getOrderNo(), msg.getIdCard(), msg.getBranchId());
        try {
            // 幂等：已处理过则直接确认
            if (reservationRepository.existsByOrderNo(msg.getOrderNo())) {
                clearPending(record.value(), msg.getProductId());
                ack.acknowledge();
                return;
            }
            persistService.confirm(msg);
            clearPending(record.value(), msg.getProductId());
            ack.acknowledge();
            log.info("落库成功: orderNo={}", msg.getOrderNo());
        } catch (Exception e) {
            // 落库失败（事务已回滚）→ 幂等回补 Redis 额度；先查后补由补偿标记 + 对账共同保证
            log.error("落库失败，触发 Redis 回补: orderNo={}", msg.getOrderNo(), e);
            compensateRedis(msg);
            clearPending(record.value(), msg.getProductId());
            // 简化处理：直接 ack，避免无限重试；生产应进入死信队列 + 定时对账
            ack.acknowledge();
        }
    }

    /**
     * 幂等回补：网点额度 +1、限购 -1（精确定位到当初扣减的分片）。
     * 与预约服务共用 {@code resv:compensated:{orderNo}} 标记，保证同一单只回补一次。
     */
    private void compensateRedis(ReservationResultMessage msg) {
        String guardKey = CommonConstants.KEY_COMPENSATED + ":" + msg.getOrderNo();
        Boolean first;
        try {
            first = redis.opsForValue().setIfAbsent(guardKey, "1", Duration.ofHours(24));
        } catch (Exception e) {
            log.error("回补去重标记失败，跳过回补（交由对账重试）: orderNo={}", msg.getOrderNo(), e);
            return;
        }
        if (!Boolean.TRUE.equals(first)) {
            log.debug("该单已回补过，跳过: orderNo={}", msg.getOrderNo());
            return;
        }

        String stockKey = CommonConstants.KEY_STOCK + ":" + msg.getProductId() + ":"
                + msg.getBranchId() + ":" + msg.getShard();
        String limitKey = CommonConstants.KEY_LIMIT + ":" + msg.getProductId();
        try {
            redis.execute(compensateStockScript, List.of(stockKey, limitKey), msg.getIdCard());
        } catch (Exception e) {
            // 标记已写下但回补失败 → 删除标记，让对账任务能够重试
            try {
                redis.delete(guardKey);
            } catch (Exception ignore) {
                // 删除失败则该单最多漏补一次，需人工介入库存对账
            }
            log.error("额度回补失败: orderNo={}", msg.getOrderNo(), e);
        }
    }

    /**
     * 清除在途单。成员就是 Kafka 原始报文（与预约服务写入的 JSON 完全一致），故可直接 ZREM。
     * 失败也无副作用：对账任务会复核 PG 后移出。
     */
    private void clearPending(String rawMessage, Long productId) {
        try {
            redis.opsForZSet().remove(CommonConstants.KEY_PENDING + ":" + productId, rawMessage);
        } catch (Exception e) {
            log.warn("清除在途单失败（对账会复核，无副作用）: {}", e.getMessage());
        }
    }
}
