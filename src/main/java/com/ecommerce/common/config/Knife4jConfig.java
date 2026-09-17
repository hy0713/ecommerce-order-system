package com.ecommerce.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j / OpenAPI 接口文档配置
 * 访问地址：http://localhost:8080/doc.html
 */
@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("轻量电商订单系统 API")
                        .description("阶段一：Spring Boot 单体后端。商品 / 购物车 / 订单 / 库存核心业务接口")
                        .version("1.0.0")
                        .contact(new Contact().name("ecommerce")));
    }
}
