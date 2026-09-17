package com.ecommerce.config;

import com.ecommerce.common.interceptor.UserIdFeignRequestInterceptor;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 配置：内部调用自动透传当前用户上下文与内部调用令牌
 */
@Configuration
public class FeignConfig {

    @Value("${ecommerce.internal.token:}")
    private String internalToken;

    @Bean
    public RequestInterceptor userIdFeignRequestInterceptor() {
        return new UserIdFeignRequestInterceptor(internalToken);
    }
}
