package com.ecommerce.common.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单明细视图
 */
@Data
public class OrderItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long productId;

    private String productName;

    /** 商品单价快照 */
    private BigDecimal productPrice;

    private Integer productQuantity;

    /** 小计 = 单价 × 数量 */
    public BigDecimal getSubtotal() {
        if (productPrice == null || productQuantity == null) {
            return BigDecimal.ZERO;
        }
        return productPrice.multiply(BigDecimal.valueOf(productQuantity));
    }
}
