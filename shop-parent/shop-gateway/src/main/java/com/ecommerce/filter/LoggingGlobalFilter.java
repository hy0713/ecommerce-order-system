package com.ecommerce.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关全局请求日志：方法 / 路径 / 响应状态 / 耗时
 */
@Slf4j
@Component
public class LoggingGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        long start = System.currentTimeMillis();
        return chain.filter(exchange).doFinally(signal -> {
            ServerHttpResponse response = exchange.getResponse();
            long cost = System.currentTimeMillis() - start;
            log.info("网关请求：{} {} -> status={}, cost={}ms",
                    request.getMethod(), request.getURI().getPath(),
                    response.getStatusCode() == null ? "-" : response.getStatusCode().value(), cost);
        });
    }

    @Override
    public int getOrder() {
        return -50;
    }
}
