package com.ecommerce.config;

import com.ecommerce.common.interceptor.UserHeaderInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册用户上下文拦截器
 * （校验内部调用令牌 + 读取网关透传的 X-User-Id / X-User-Role）
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final UserHeaderInterceptor userHeaderInterceptor;

    public WebMvcConfig(UserHeaderInterceptor userHeaderInterceptor) {
        this.userHeaderInterceptor = userHeaderInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 只拦截业务接口，接口文档保持可访问
        registry.addInterceptor(userHeaderInterceptor).addPathPatterns("/api/**");
    }
}
