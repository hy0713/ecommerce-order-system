package com.ecommerce.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecommerce.common.result.Result;
import com.ecommerce.common.util.UserContext;
import com.ecommerce.dto.ProductDTO;
import com.ecommerce.dto.ProductQueryDTO;
import com.ecommerce.service.ProductService;
import com.ecommerce.vo.ProductVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品接口
 */
@Tag(name = "商品管理")
@RestController
@RequestMapping("/api/product")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @Operation(summary = "商品分页查询")
    @GetMapping("/page")
    public Result<Page<ProductVO>> page(@Validated ProductQueryDTO queryDTO) {
        return Result.success(productService.page(queryDTO));
    }

    @Operation(summary = "商品详情")
    @GetMapping("/{id}")
    public Result<ProductVO> detail(@PathVariable Long id) {
        return Result.success(productService.detail(id));
    }

    @Operation(summary = "新增商品（管理员）")
    @PostMapping
    public Result<Long> add(@Validated @RequestBody ProductDTO productDTO) {
        UserContext.requireAdmin();
        return Result.success(productService.add(productDTO));
    }

    @Operation(summary = "修改商品（管理员）")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Validated @RequestBody ProductDTO productDTO) {
        UserContext.requireAdmin();
        productService.update(id, productDTO);
        return Result.success();
    }

    @Operation(summary = "商品上下架（管理员）")
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        UserContext.requireAdmin();
        productService.updateStatus(id, status);
        return Result.success();
    }

    @Operation(summary = "删除商品（管理员）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        UserContext.requireAdmin();
        productService.delete(id);
        return Result.success();
    }
}
