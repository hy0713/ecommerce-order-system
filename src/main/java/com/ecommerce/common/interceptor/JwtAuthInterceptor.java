package com.ecommerce.common.interceptor;

import com.ecommerce.common.constant.AuthConstant;
import com.ecommerce.common.constant.RedisKeyConstant;
import com.ecommerce.common.result.Result;
import com.ecommerce.common.result.ResultCode;
import com.ecommerce.common.util.JwtUtil;
import com.ecommerce.common.util.RedisUtil;
import com.ecommerce.common.util.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;

/**
 * JWT 登录鉴权拦截器
 *
 * <p>鉴权判定改为「方法 + 路径」精确匹配白名单（{@link AuthConstant#isPublic}），
 * 不再使用 excludePathPatterns —— 后者只能按路径排除，会把同一前缀下的写接口
 * 一起放行（历史漏洞：/api/product/** 曾让商品改价/改库存/删除全部免登录）。
 *
 * <p>校验通过后注入用户 ID 与角色：验签 + Redis 有效性双校验。
 */
@Slf4j
@Component
public class JwtAuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final RedisUtil redisUtil;
    private final ObjectMapper objectMapper;

    public JwtAuthInterceptor(JwtUtil jwtUtil, RedisUtil redisUtil, ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.redisUtil = redisUtil;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 匿名白名单：方法 + 路径精确匹配
        if (AuthConstant.isPublic(request.getMethod(), request.getRequestURI())) {
            return true;
        }
        // 2. 必须携带 Authorization: Bearer {token}
        String authHeader = request.getHeader(AuthConstant.HEADER_AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(AuthConstant.BEARER_PREFIX)) {
            return reject(response);
        }
        String token = authHeader.substring(AuthConstant.BEARER_PREFIX.length());
        Long userId = jwtUtil.parseUserId(token);
        // 3. 双重校验：签名有效 + token 仍在 Redis（支持登出失效）
        if (userId == null || !redisUtil.hasKey(RedisKeyConstant.TOKEN_PREFIX + token)) {
            return reject(response);
        }
        UserContext.setUserId(userId);
        UserContext.setRole(jwtUtil.parseRole(token));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    private boolean reject(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Result<Void> result = Result.error(ResultCode.UNAUTHORIZED);
        response.getWriter().write(objectMapper.writeValueAsString(result));
        return false;
    }
}
