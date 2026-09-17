package com.ecommerce.common.config;

import com.ecommerce.common.interceptor.JwtAuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：全局跨域 + JWT 鉴权拦截器。
 *
 * <p>拦截器只负责拦截 /api/**，是否放行由 {@link JwtAuthInterceptor} 内部按
 * 「方法 + 路径」白名单判定（excludePathPatterns 只能按路径排除，无法区分读写方法）。
 *
 * <p>跨域来源改为显式配置：原先 allowedOriginPatterns("*") + allowCredentials(true)
 * 会允许任意站点携带凭据发起请求。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtAuthInterceptor jwtAuthInterceptor;

    @Value("${ecommerce.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173,http://localhost:3000}")
    private String[] allowedOrigins;

    public WebMvcConfig(JwtAuthInterceptor jwtAuthInterceptor) {
        this.jwtAuthInterceptor = jwtAuthInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                // 前端使用 Bearer Token（非 Cookie），无需凭据模式
                .allowCredentials(false)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtAuthInterceptor).addPathPatterns("/api/**");
    }
}
