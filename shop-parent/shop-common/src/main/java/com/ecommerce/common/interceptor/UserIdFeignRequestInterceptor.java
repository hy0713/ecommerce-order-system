package com.ecommerce.common.interceptor;

import cn.hutool.core.util.StrUtil;
import com.ecommerce.common.constant.AuthConstant;
import com.ecommerce.common.util.UserContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * Feign 请求拦截器：服务间调用时自动透传当前用户上下文与内部调用令牌
 */
public class UserIdFeignRequestInterceptor implements RequestInterceptor {

    private final String internalToken;

    public UserIdFeignRequestInterceptor(String internalToken) {
        this.internalToken = internalToken;
    }

    @Override
    public void apply(RequestTemplate template) {
        Long userId = UserContext.getUserId();
        if (userId != null) {
            template.header(AuthConstant.HEADER_USER_ID, String.valueOf(userId));
        }
        String role = UserContext.getRole();
        if (StrUtil.isNotBlank(role)) {
            template.header(AuthConstant.HEADER_USER_ROLE, role);
        }
        // 服务间调用同样需要内部令牌，否则会被下游服务的内部令牌校验拒绝
        if (StrUtil.isNotBlank(internalToken)) {
            template.header(AuthConstant.HEADER_INTERNAL_TOKEN, internalToken);
        }
    }
}
