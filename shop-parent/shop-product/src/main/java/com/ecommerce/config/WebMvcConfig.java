package com.ecommerce.config;

import com.ecommerce.common.interceptor.UserHeaderInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册用户上下文拦截器。
 *
 * <p>商品服务此前没有该配置，导致既不做内部令牌校验、也不注入用户上下文，
 * 直连端口即可调用任意接口。跨域已上移网关，业务服务不再配置。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final UserHeaderInterceptor userHeaderInterceptor;

    public WebMvcConfig(UserHeaderInterceptor userHeaderInterceptor) {
        this.userHeaderInterceptor = userHeaderInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 只拦截业务接口，接口文档（/doc.html、/v3/api-docs）保持可访问
        registry.addInterceptor(userHeaderInterceptor).addPathPatterns("/api/**");
    }
}
