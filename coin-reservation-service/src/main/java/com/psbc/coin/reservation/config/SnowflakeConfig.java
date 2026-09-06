package com.psbc.coin.reservation.config;

import com.psbc.coin.common.util.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SnowflakeConfig {

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(@Value("${coin.worker-id:1}") long workerId) {
        return new SnowflakeIdGenerator(workerId);
    }
}
