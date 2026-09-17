package com.ecommerce.filter;

import cn.hutool.core.util.StrUtil;
import com.ecommerce.common.constant.AuthConstant;
import com.ecommerce.common.feign.TokenValidateDTO;
import com.ecommerce.common.feign.TokenValidateResult;
import com.ecommerce.common.result.Result;
import com.ecommerce.common.result.ResultCode;
import com.ecommerce.common.util.InternalTokenGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 网关全局鉴权过滤器。
 *
 * <p>职责：
 * <ol>
 *   <li><b>拒绝内部接口</b>：{@code /api/internal/**} 属服务间接口，网关直接 404；</li>
 *   <li><b>剥离伪造头</b>：任何来自客户端的 X-User-Id / X-User-Role / X-Internal-Token
 *       一律移除，避免绕过鉴权冒充身份；</li>
 *   <li><b>注入内部令牌</b>：白名单与鉴权通过的请求都注入 X-Internal-Token，
 *       业务服务据此判定「请求确实来自网关」；</li>
 *   <li><b>鉴权</b>：白名单直接放行；「可选鉴权」端点无 token 按匿名放行、带 token 必须有效；
 *       其余请求一律要求有效 token，校验通过后透传 X-User-Id / X-User-Role。</li>
 * </ol>
 */
@Slf4j
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    /** 调用用户服务校验 token 的连接超时（毫秒） */
    private static final int VALIDATE_CONNECT_TIMEOUT_MS = 2000;
    /** 调用用户服务校验 token 的响应超时 */
    private static final Duration VALIDATE_RESPONSE_TIMEOUT = Duration.ofSeconds(3);

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final ObjectMapper objectMapper;
    private final DiscoveryClient discoveryClient;
    private final WebClient webClient;
    private final AtomicInteger roundRobin = new AtomicInteger(0);

    private final String internalToken;

    public AuthGlobalFilter(DiscoveryClient discoveryClient, ObjectMapper objectMapper,
                            @Value("${ecommerce.internal.token:}") String internalToken) {
        // 令牌缺失直接拒绝启动：留空时下面的 sanitize() 不会注入该头，
        // 而业务服务在旧实现里会"跳过校验"，整条内部令牌防线静默失效 ——
        // 直连服务端口伪造 X-User-Id 就能冒充任意用户。
        InternalTokenGuard.validate(internalToken, "网关");
        this.internalToken = internalToken;
        this.objectMapper = objectMapper;
        this.discoveryClient = discoveryClient;
        // 显式配置连接与响应超时：否则用户服务异常时网关请求会长时间挂起并耗尽连接
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, VALIDATE_CONNECT_TIMEOUT_MS)
                .responseTimeout(VALIDATE_RESPONSE_TIMEOUT);
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 0. 路径穿越防御：必须先于「内部接口」判断和路由。
        // /api/product/../internal/stock/deduct 不以 /api/internal/ 开头，能绕过下面的前缀拦截，
        // 却命中 /api/product/** 的路由被原样转发，下游 Tomcat 归一化后即可触达内部库存接口。
        if (AuthConstant.hasDotSegment(path)) {
            log.warn("拒绝含路径穿越段的请求：path={}, remote={}", path, remoteAddr(exchange));
            return notFound(exchange);
        }

        // 1. 内部接口不对公网暴露
        if (AuthConstant.isInternalPath(path)) {
            log.warn("拒绝外部访问内部接口：path={}, remote={}", path, remoteAddr(exchange));
            return notFound(exchange);
        }

        // 2. 白名单：无需鉴权，但仍注入内部令牌供下游校验来源
        if (AuthConstant.isPublic(exchange.getRequest().getMethodValue(), path)) {
            return chain.filter(exchange.mutate()
                    .request(sanitize(exchange.getRequest(), null, null))
                    .build());
        }

        // 2.5 可选鉴权：无 token 按匿名放行；带了 token 就必须有效
        //     （不能把「token 过期」当成游客，否则前端收不到 401、无法提示重新登录）
        if (AuthConstant.isOptionalAuth(exchange.getRequest().getMethodValue(), path)) {
            String optionalToken = extractToken(exchange.getRequest());
            if (StrUtil.isBlank(optionalToken)) {
                return chain.filter(exchange.mutate()
                        .request(sanitize(exchange.getRequest(), null, null))
                        .build());
            }
            return authenticate(exchange, chain, optionalToken, path);
        }

        // 3. 非白名单：必须携带有效 token
        String token = extractToken(exchange.getRequest());
        if (StrUtil.isBlank(token)) {
            return reject(exchange, Result.error(ResultCode.UNAUTHORIZED));
        }
        return authenticate(exchange, chain, token, path);
    }

    /**
     * 调用用户服务校验 token，通过后注入 X-User-Id / X-User-Role 再转发。
     * 白名单与可选鉴权共用同一条校验链路，避免两处实现漂移。
     */
    private Mono<Void> authenticate(ServerWebExchange exchange, GatewayFilterChain chain,
                                    String token, String path) {
        return resolveUserServiceUrl()
                .flatMap(baseUrl -> {
                    TokenValidateDTO dto = new TokenValidateDTO();
                    dto.setToken(token);
                    return webClient.post()
                            .uri(baseUrl + "/api/auth/validate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(AuthConstant.HEADER_INTERNAL_TOKEN, internalToken)
                            .bodyValue(dto)
                            .retrieve()
                            .bodyToMono(Result.class);
                })
                .flatMap(result -> {
                    if (result == null || result.getCode() != ResultCode.SUCCESS.getCode()
                            || result.getData() == null) {
                        return reject(exchange, Result.error(ResultCode.UNAUTHORIZED));
                    }
                    TokenValidateResult validateResult = objectMapper.convertValue(
                            result.getData(), TokenValidateResult.class);
                    if (!validateResult.isValid() || validateResult.getUserId() == null) {
                        return reject(exchange, Result.error(ResultCode.UNAUTHORIZED));
                    }
                    String role = validateResult.getRole() == null
                            ? AuthConstant.ROLE_USER : validateResult.getRole();
                    ServerHttpRequest mutatedRequest = sanitize(
                            exchange.getRequest(), String.valueOf(validateResult.getUserId()), role);
                    return chain.filter(exchange.mutate().request(mutatedRequest).build());
                })
                .onErrorResume(e -> {
                    log.error("网关 token 校验调用用户服务异常：path={}", path, e);
                    return reject(exchange, Result.error(ResultCode.ERROR));
                });
    }

    /**
     * 重建请求头：先剔除客户端可能伪造的身份/内部令牌头，再写入网关认定值。
     */
    private ServerHttpRequest sanitize(ServerHttpRequest request, String userId, String role) {
        return request.mutate()
                .headers(headers -> {
                    headers.remove(AuthConstant.HEADER_USER_ID);
                    headers.remove(AuthConstant.HEADER_USER_ROLE);
                    headers.remove(AuthConstant.HEADER_INTERNAL_TOKEN);
                    // 令牌非空由构造函数保证（InternalTokenGuard）
                    headers.set(AuthConstant.HEADER_INTERNAL_TOKEN, internalToken);
                    if (userId != null) {
                        headers.set(AuthConstant.HEADER_USER_ID, userId);
                        if (StrUtil.isNotBlank(role)) {
                            headers.set(AuthConstant.HEADER_USER_ROLE, role);
                        }
                    }
                })
                .build();
    }

    @Override
    public int getOrder() {
        return -100;
    }

    /**
     * 通过服务发现解析用户服务实例地址，多实例轮询以分摊校验流量
     */
    private Mono<String> resolveUserServiceUrl() {
        return Mono.fromSupplier(() -> {
            List<ServiceInstance> instances = discoveryClient.getInstances("shop-user");
            if (instances == null || instances.isEmpty()) {
                throw new IllegalStateException("shop-user 无可用实例");
            }
            int index = Math.floorMod(roundRobin.getAndIncrement(), instances.size());
            ServiceInstance instance = instances.get(index);
            return "http://" + instance.getHost() + ":" + instance.getPort();
        });
    }

    private String extractToken(ServerHttpRequest request) {
        String authHeader = request.getHeaders().getFirst(AuthConstant.HEADER_AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith(AuthConstant.BEARER_PREFIX)) {
            return authHeader.substring(AuthConstant.BEARER_PREFIX.length());
        }
        return null;
    }

    private String remoteAddr(ServerWebExchange exchange) {
        return exchange.getRequest().getRemoteAddress() == null
                ? "unknown" : exchange.getRequest().getRemoteAddress().toString();
    }

    private Mono<Void> reject(ServerWebExchange exchange, Result<?> result) {
        return writeJson(exchange, HttpStatus.UNAUTHORIZED, result);
    }

    private Mono<Void> notFound(ServerWebExchange exchange) {
        return writeJson(exchange, HttpStatus.NOT_FOUND, Result.error(ResultCode.NOT_FOUND));
    }

    private Mono<Void> writeJson(ServerWebExchange exchange, HttpStatus status, Result<?> result) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(result);
        } catch (Exception e) {
            bytes = "{\"code\":500,\"message\":\"系统繁忙\"}".getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
