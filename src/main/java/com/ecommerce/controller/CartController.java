package com.ecommerce.controller;

import com.ecommerce.common.result.Result;
import com.ecommerce.common.util.UserContext;
import com.ecommerce.dto.CartAddDTO;
import com.ecommerce.dto.CartUpdateDTO;
import com.ecommerce.service.CartService;
import com.ecommerce.vo.CartItemVO;
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

import java.util.List;

/**
 * 购物车接口
 */
@Tag(name = "购物车管理")
@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @Operation(summary = "购物车列表")
    @GetMapping("/list")
    public Result<List<CartItemVO>> list() {
        return Result.success(cartService.list(UserContext.getUserId()));
    }

    @Operation(summary = "添加商品到购物车")
    @PostMapping
    public Result<Void> add(@Validated @RequestBody CartAddDTO cartAddDTO) {
        cartService.add(UserContext.getUserId(), cartAddDTO);
        return Result.success();
    }

    @Operation(summary = "修改购物车商品数量")
    @PutMapping("/{id}/quantity")
    public Result<Void> updateQuantity(@PathVariable Long id, @Validated @RequestBody CartUpdateDTO cartUpdateDTO) {
        cartService.updateQuantity(UserContext.getUserId(), id, cartUpdateDTO.getQuantity());
        return Result.success();
    }

    @Operation(summary = "删除购物车条目")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        cartService.delete(UserContext.getUserId(), id);
        return Result.success();
    }

    @Operation(summary = "全选 / 取消全选")
    @PutMapping("/select-all")
    public Result<Void> selectAll(@RequestParam boolean selected) {
        cartService.selectAll(UserContext.getUserId(), selected);
        return Result.success();
    }

    @Operation(summary = "勾选 / 取消勾选单个条目")
    @PutMapping("/{id}/selected")
    public Result<Void> selectOne(@PathVariable Long id, @RequestParam boolean selected) {
        cartService.selectOne(UserContext.getUserId(), id, selected);
        return Result.success();
    }
}
