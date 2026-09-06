package com.psbc.coin.order.config;

import com.psbc.coin.common.util.SnowflakeIdGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SnowflakeConfig {

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator() {
        // order-service 使用 workerId=2，避免与 reservation-service(workerId=1) 冲突
        return new SnowflakeIdGenerator(2);
    }
}
