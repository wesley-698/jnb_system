package com.psbc.coin.reservation.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.CircuitBreakerStrategy;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * ② Sentinel 规则：熔断（保护 Redis 调用方）+ 限流（保护降级后的主库）。
 */
@Slf4j
@Configuration
public class SentinelRuleConfig {

    @Value("${coin.degrade.db-direct-qps-limit:200}")
    private double dbDirectQpsLimit;

    @PostConstruct
    public void initRules() {
        // 熔断：Redis 主链路异常比例超过 50% → 熔断 10 秒，快速失败
        DegradeRule redisDegrade = new DegradeRule("reservationSubmit")
                .setGrade(CircuitBreakerStrategy.ERROR_RATIO.getType())
                .setCount(0.5)
                .setTimeWindow(10)
                .setMinRequestAmount(5)
                .setStatIntervalMs(10000);
        DegradeRuleManager.loadRules(List.of(redisDegrade));

        // 限流：降级模式走 DB 直扣，必须强限流把并发压到主库容量内
        FlowRule dbDirectFlow = new FlowRule("dbDirectSubmit")
                .setGrade(RuleConstant.FLOW_GRADE_QPS)
                .setCount(dbDirectQpsLimit);
        FlowRuleManager.loadRules(List.of(dbDirectFlow));

        log.info("Sentinel 规则已加载: reservationSubmit 熔断(异常比例50%), dbDirectSubmit 限流({} QPS)",
                dbDirectQpsLimit);
    }
}
