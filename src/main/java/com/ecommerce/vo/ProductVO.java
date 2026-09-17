package com.ecommerce.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品视图
 */
@Data
public class ProductVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long categoryId;

    /** 分类名称 */
    private String categoryName;

    private String name;

    private BigDecimal price;

    private Integer stock;

    private String description;

    private String icon;

    /** 状态：0 下架 1 上架 */
    private Integer status;

    private LocalDateTime createTime;
}
