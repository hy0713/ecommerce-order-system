package com.ecommerce.common.feign;

import com.ecommerce.common.result.Result;
import com.ecommerce.common.vo.ProductVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 商品服务 Feign 客户端（订单服务调用）
 *
 * <p>注意：库存扣减/回补属于内部接口，路径前缀为 {@code /api/internal/}，
 * 该前缀不加入匿名白名单、网关也无对应路由，仅服务间调用可达。
 */
@FeignClient(name = "shop-product")
public interface ProductFeignClient {

    /**
     * 查询商品实时详情（直查数据库，供加购/下单业务校验，不读缓存）
     */
    @GetMapping("/api/product/{id}/fresh")
    Result<ProductVO> getById(@PathVariable("id") Long id);

    /**
     * 批量查询商品（购物车列表用），ids 逗号分隔
     */
    @GetMapping("/api/product/ids")
    Result<List<ProductVO>> listByIds(@RequestParam("ids") String ids);

    /**
     * 扣减库存（商品服务内：Redisson 锁 + 乐观锁，防超卖）
     */
    @PostMapping("/api/internal/stock/deduct")
    Result<StockDeductResult> deductStock(@RequestBody StockDeductDTO dto);

    /**
     * 回补库存（取消订单 / 超时自动取消 / 下单失败补偿）
     */
    @PostMapping("/api/internal/stock/restore")
    Result<Void> restoreStock(@RequestBody StockRestoreDTO dto);
}
