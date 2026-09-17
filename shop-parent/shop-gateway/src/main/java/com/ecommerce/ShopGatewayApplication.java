package com.ecommerce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 网关服务启动类（shop-gateway，端口 8080）
 * 统一入口：路由转发 / 全局鉴权 / 跨域 / 请求日志 / Sentinel 限流
 * 仅扫描网关自身组件包（common 中的 Servlet 相关组件如拦截器、RedisUtil 不加载，
 * 避免 WebFlux 环境下类依赖缺失）
 */
@SpringBootApplication(scanBasePackages = {"com.ecommerce.filter", "com.ecommerce.config"})
@EnableDiscoveryClient
public class ShopGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShopGatewayApplication.class, args);
    }
}
