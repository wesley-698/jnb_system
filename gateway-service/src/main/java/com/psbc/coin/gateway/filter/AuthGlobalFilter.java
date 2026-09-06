package com.psbc.coin.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 全局鉴权过滤器（骨架版）。
 * 生产环境：校验 JWT，解析 userId 并以 header 透传给下游服务。
 * 此处仅做 token 存在性校验占位，真实 JWT 校验接入安全组件。
 */
@Slf4j
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    /** 需要鉴权的路径前缀 */
    private static final String[] PROTECTED_PREFIXES = {
            "/api/reservation/", "/api/order/", "/api/exchange/"
    };

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        if (requiresAuth(path)) {
            String token = request.getHeaders().getFirst("Authorization");
            if (token == null || token.isBlank()) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }
            // TODO: 真实 JWT 解析，校验签名/过期，解析 userId 透传
            log.debug("鉴权通过(占位): path={}", path);
        }
        return chain.filter(exchange);
    }

    private boolean requiresAuth(String path) {
        for (String prefix : PROTECTED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
