package com.psbc.coin.user.controller;

import com.psbc.coin.common.constant.CommonConstants;
import com.psbc.coin.common.result.Result;
import com.psbc.coin.common.result.ResultCode;
import com.psbc.coin.user.entity.Qualification;
import com.psbc.coin.user.repository.QualificationRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Optional;

/**
 * 实名预填接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class QualificationController {

    private final QualificationRepository qualificationRepository;
    private final StringRedisTemplate redis;

    /** 资格标记有效期（小时）。真实业务里资格在预约期内一直有效，不应短于预约期。 */
    @Value("${coin.qualification-ttl-hours:72}")
    private long qualificationTtlHours;

    /**
     * 实名预填（三要素校验此处简化为 mock，生产对接公安/实名系统）。
     * 校验通过后写 t_qualification，并在 Redis 打资格标记，供预约服务校验。
     *
     * <p><b>本接口是幂等的</b>：同一身份证重复预填视为「已通过」并刷新资格标记，
     * 而不是返回失败。
     *
     * <p>为什么必须这样：资格标记 {@code qual:ok:{userId}} 带 TTL，而 {@code t_qualification}
     * 里的身份证记录是永久的。若重复预填直接返回失败且不刷新标记，用户的资格一旦过期
     * 就再也补不回来（DB 里已存在记录 → 永远走失败分支），此后每次提交预约都会拿到
     * {@code 1004 未通过实名预填，无预约资格}。
     */
    @PostMapping("/qualify")
    public Result<Void> qualify(@RequestBody QualifyRequest request) {
        Long effectiveUserId = request.getUserId();
        Optional<Qualification> existing = qualificationRepository.findByIdCard(request.getIdCard());

        if (existing.isPresent()) {
            Qualification q = existing.get();
            // 一张身份证只对应一个用户；不一致说明调用方传错了 userId，
            // 这里直接报错，避免用户带着错误的 userId 去提交预约、只拿到 1004 而不知原因。
            if (q.getUserId() != null && !q.getUserId().equals(request.getUserId())) {
                log.warn("实名预填 userId 与已登记不一致: idCard={}, 已登记 userId={}, 本次 userId={}",
                        request.getIdCard(), q.getUserId(), request.getUserId());
                return Result.fail(ResultCode.PARAM_ERROR);
            }
            effectiveUserId = q.getUserId() != null ? q.getUserId() : request.getUserId();
            log.info("实名记录已存在，刷新资格标记: idCard={}, userId={}", request.getIdCard(), effectiveUserId);
        } else {
            Qualification q = new Qualification();
            q.setUserId(request.getUserId());
            q.setIdCard(request.getIdCard());
            q.setName(request.getName());
            q.setPhone(request.getPhone());
            q.setStatus(1);
            qualificationRepository.save(q);
        }

        // 无论新建还是已存在，都刷新资格标记（重复预填 = 续期，不会把用户卡死在 1004）
        redis.opsForValue().set(CommonConstants.KEY_QUALIFICATION + ":" + effectiveUserId,
                "1", Duration.ofHours(qualificationTtlHours));
        return Result.success();
    }

    @Data
    public static class QualifyRequest {
        private Long userId;
        private String idCard;
        private String name;
        private String phone;
    }
}
