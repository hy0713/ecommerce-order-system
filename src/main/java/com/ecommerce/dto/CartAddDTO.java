package com.ecommerce.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

/**
 * 加入购物车入参
 */
@Data
public class CartAddDTO {

    @NotNull(message = "商品ID不能为空")
    private Long productId;

    @NotNull(message = "购买数量不能为空")
    @Min(value = 1, message = "购买数量最小为 1")
    @Max(value = 999, message = "购买数量最大为 999")
    private Integer quantity;
}
