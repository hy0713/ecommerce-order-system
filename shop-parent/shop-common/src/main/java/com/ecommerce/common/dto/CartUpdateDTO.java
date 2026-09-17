package com.ecommerce.common.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

/**
 * 购物车商品数量修改入参
 */
@Data
public class CartUpdateDTO {

    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量最小为 1")
    @Max(value = 999, message = "数量最大为 999")
    private Integer quantity;
}
