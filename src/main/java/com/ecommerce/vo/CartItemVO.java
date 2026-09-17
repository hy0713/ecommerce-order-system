package com.ecommerce.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 购物车条目视图（实时关联商品最新价格与状态）
 */
@Data
public class CartItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 购物车条目ID */
    private Long id;

    private Long productId;

    private String productName;

    /** 商品当前价格 */
    private BigDecimal productPrice;

    private String productIcon;

    private Integer quantity;

    /** 是否选中：0 未选 1 选中 */
    private Integer selected;

    /** 商品当前库存 */
    private Integer stock;

    /** 商品状态：0 下架 1 上架 */
    private Integer productStatus;

    /** 是否异常（已下架或库存不足） */
    private Boolean invalid;
}
