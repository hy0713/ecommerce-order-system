package com.ecommerce.common.feign;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 库存回补入参（取消订单/超时取消 → 商品服务）
 */
@Data
public class StockRestoreDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "商品ID不能为空")
    private Long productId;

    @NotNull(message = "回补数量不能为空")
    @Min(value = 1, message = "回补数量最小为 1")
    private Integer quantity;
}
