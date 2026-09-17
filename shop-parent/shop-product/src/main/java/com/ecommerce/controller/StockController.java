package com.ecommerce.controller;

import com.ecommerce.common.feign.StockDeductDTO;
import com.ecommerce.common.feign.StockDeductResult;
import com.ecommerce.common.feign.StockRestoreDTO;
import com.ecommerce.common.result.Result;
import com.ecommerce.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 库存接口（仅服务间调用）
 *
 * <p>路径刻意放在 {@code /api/internal/} 前缀下：
 * <ul>
 *   <li>该前缀<b>不在匿名白名单</b>中，外部请求必须经过网关鉴权；</li>
 *   <li>网关没有到该前缀的路由，且 AuthGlobalFilter 会直接拒绝外部访问；</li>
 *   <li>服务侧 {@link com.ecommerce.common.interceptor.UserHeaderInterceptor}
 *       校验内部令牌，直连服务端口也无法调用。</li>
 * </ul>
 * 历史问题：本接口原路径为 {@code /api/product/stock}，被白名单通配
 * {@code /api/product/**} 覆盖，导致任何人可匿名刷库存/清库存。
 */
@Tag(name = "库存管理（内部）")
@RestController
@RequestMapping("/api/internal/stock")
public class StockController {

    private final ProductService productService;

    public StockController(ProductService productService) {
        this.productService = productService;
    }

    @Operation(summary = "扣减库存（分布式锁 + 乐观锁）")
    @PostMapping("/deduct")
    public Result<StockDeductResult> deductStock(@Validated @RequestBody StockDeductDTO dto) {
        return Result.success(productService.deductStock(dto));
    }

    @Operation(summary = "回补库存")
    @PostMapping("/restore")
    public Result<Void> restoreStock(@Validated @RequestBody StockRestoreDTO dto) {
        productService.restoreStock(dto.getProductId(), dto.getQuantity());
        return Result.success();
    }
}
