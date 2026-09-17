package com.ecommerce.common.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 商品新增 / 修改入参
 */
@Data
public class ProductDTO {

    @NotNull(message = "所属分类不能为空")
    private Long categoryId;

    @NotBlank(message = "商品名称不能为空")
    @Size(max = 128, message = "商品名称长度不能超过 128 位")
    private String name;

    @NotNull(message = "商品单价不能为空")
    @DecimalMin(value = "0.01", message = "商品单价必须大于 0")
    private BigDecimal price;

    @NotNull(message = "库存数量不能为空")
    @Min(value = 0, message = "库存数量不能小于 0")
    private Integer stock;

    @Size(max = 1000, message = "商品描述长度不能超过 1000 位")
    private String description;

    @Size(max = 255, message = "商品主图地址长度不能超过 255 位")
    private String icon;

    @Min(value = 0, message = "商品状态参数错误")
    @Max(value = 1, message = "商品状态参数错误")
    private Integer status;
}
