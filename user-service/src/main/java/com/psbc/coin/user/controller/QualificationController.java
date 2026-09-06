package com.psbc.coin.user.controller;

import com.psbc.coin.common.constant.CommonConstants;
import com.psbc.coin.common.result.Result;
import com.psbc.coin.common.result.ResultCode;
import com.psbc.coin.user.entity.Qualification;
import com.psbc.coin.user.repository.QualificationRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

/**
 * 实名预填接口。
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class QualificationController {

    private final QualificationRepository qualificationRepository;
    private final StringRedisTemplate redis;

    /**
     * 实名预填（三要素校验此处简化为 mock，生产对接公安/实名系统）。
     * 校验通过后写 t_qualification，并在 Redis 打资格标记，供预约服务校验。
     */
    @PostMapping("/qualify")
    public Result<Void> qualify(@RequestBody QualifyRequest request) {
        if (qualificationRepository.existsByIdCard(request.getIdCard())) {
            return Result.fail(ResultCode.DUPLICATE_SUBMIT);
        }
        Qualification q = new Qualification();
        q.setUserId(request.getUserId());
        q.setIdCard(request.getIdCard());
        q.setName(request.getName());
        q.setPhone(request.getPhone());
        q.setStatus(1);
        qualificationRepository.save(q);

        // 打资格标记（预约服务阶段2 校验此 key）
        redis.opsForValue().set(CommonConstants.KEY_QUALIFICATION + ":" + request.getUserId(),
                "1", Duration.ofHours(1));
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
