package com.ecommerce.common.interceptor;

import cn.hutool.core.util.StrUtil;
import com.ecommerce.common.constant.AuthConstant;
import com.ecommerce.common.result.Result;
import com.ecommerce.common.result.ResultCode;
import com.ecommerce.common.util.InternalTokenGuard;
import com.ecommerce.common.util.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;

/**
 * 用户上下文拦截器（业务服务侧）。
 *
 * <p>职责一：内部令牌校验。业务服务端口不应被外部直接访问，若请求未携带与配置一致的
 * X-Internal-Token，说明它绕过了网关，直接拒绝，避免伪造 X-User-Id 冒充任意用户。
 * 令牌本身缺失时由 {@link InternalTokenGuard} 在启动阶段直接拒绝（fail-fast），
 * 不会退化成"跳过校验"。
 *
 * <p>职责二：读取网关透传的 X-User-Id / X-User-Role，注入当前请求线程上下文。
 */
@Slf4j
@Component
public class UserHeaderInterceptor implements HandlerInterceptor {

    /** 内部调用令牌；缺失即启动失败（见 InternalTokenGuard），不做"留空跳过校验"的降级 */
    private final String internalToken;
    private final ObjectMapper objectMapper;

    public UserHeaderInterceptor(@Value("${ecommerce.internal.token:}") String internalToken,
                                 ObjectMapper objectMapper) {
        InternalTokenGuard.validate(internalToken, "业务服务");
        this.internalToken = internalToken;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 内部令牌校验：阻断绕过网关的直连请求
        //    令牌在构造时已保证非空（缺失直接启动失败），这里恒等比较即可
        if (!internalToken.equals(request.getHeader(AuthConstant.HEADER_INTERNAL_TOKEN))) {
            log.warn("拒绝缺失或错误内部令牌的请求：method={}, uri={}, remote={}",
                    request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
            return reject(response, ResultCode.FORBIDDEN, "内部接口不允许直接访问");
        }
        // 2. 注入用户上下文（白名单接口可能没有用户信息，属正常情况）
        String userId = request.getHeader(AuthConstant.HEADER_USER_ID);
        if (StrUtil.isNotBlank(userId)) {
            try {
                UserContext.setUserId(Long.valueOf(userId));
            } catch (NumberFormatException e) {
                log.warn("非法的 X-User-Id 请求头：{}", userId);
                return reject(response, ResultCode.UNAUTHORIZED);
            }
        }
        UserContext.setRole(request.getHeader(AuthConstant.HEADER_USER_ROLE));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    private boolean reject(HttpServletResponse response, ResultCode resultCode) throws Exception {
        return reject(response, resultCode, resultCode.getMessage());
    }

    private boolean reject(HttpServletResponse response, ResultCode resultCode, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.error(resultCode.getCode(), message)));
        return false;
    }
}
