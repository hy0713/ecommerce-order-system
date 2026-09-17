package com.ecommerce.filter;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.ecommerce.common.result.Result;
import com.ecommerce.common.result.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 限流异常兜底过滤器：Sentinel 网关过滤器的 BlockException 在 gateway 3.x 下
 * 会以异常形式冒泡，此处统一捕获并返回 429 业务错误码
 */
@Slf4j
@Component
public class BlockExceptionFilter implements GlobalFilter, Ordered {

    private final ObjectMapper objectMapper;

    public BlockExceptionFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange)
                .onErrorResume(BlockException.class, e -> {
                    log.warn("网关限流触发：path={}, ex={}", exchange.getRequest().getURI().getPath(),
                            e.getClass().getSimpleName());
                    return writeTooManyRequests(exchange);
                });
    }

    private Mono<Void> writeTooManyRequests(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(Result.error(ResultCode.TOO_MANY_REQUESTS));
        } catch (Exception ex) {
            bytes = "{\"code\":429,\"message\":\"请求过于频繁，请稍后重试\"}".getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // 必须早于 SentinelGatewayFilter 才能捕获其抛出的 BlockException。
        // Sentinel 的 order 已在 SentinelConfig 中显式设为 HIGHEST_PRECEDENCE + 1，
        // 与本值（MIN_VALUE）形成确定顺序，不再依赖同序时的偶然排序。
        return Integer.MIN_VALUE;
    }
}
