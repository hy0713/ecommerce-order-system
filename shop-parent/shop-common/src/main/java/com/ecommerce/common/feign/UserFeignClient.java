package com.ecommerce.common.feign;

import com.ecommerce.common.entity.UserAddress;
import com.ecommerce.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 用户服务 Feign 客户端（订单服务调用）
 * 地址归属校验在用户服务完成：Feign 请求拦截器自动透传 X-User-Id
 */
@FeignClient(name = "shop-user", path = "/api/user")
public interface UserFeignClient {

    /**
     * 查询收货地址（校验归属当前用户）
     */
    @GetMapping("/address/{id}")
    Result<UserAddress> getAddress(@PathVariable("id") Long id);
}
