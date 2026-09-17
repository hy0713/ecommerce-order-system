package com.ecommerce.common.feign;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 库存扣减入参（订单服务 → 商品服务）
 */
@Data
public class StockDeductDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "商品ID不能为空")
    private Long productId;

    @NotNull(message = "扣减数量不能为空")
    @Min(value = 1, message = "扣减数量最小为 1")
    @Max(value = 9999, message = "扣减数量最大为 9999")
    private Integer quantity;
}
