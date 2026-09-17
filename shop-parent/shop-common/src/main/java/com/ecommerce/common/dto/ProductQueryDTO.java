package com.ecommerce.common.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import java.math.BigDecimal;

/**
 * 商品分页查询入参
 */
@Data
public class ProductQueryDTO {

    /** 关键词：商品名称模糊匹配 */
    private String keyword;

    /** 分类ID */
    private Long categoryId;

    /** 最低价格 */
    private BigDecimal minPrice;

    /** 最高价格 */
    private BigDecimal maxPrice;

    /** 商品状态：null 查全部 */
    private Integer status;

    /** 页码，默认 1 */
    @Min(value = 1, message = "页码最小为 1")
    private Integer pageNum = 1;

    /** 每页数量，默认 10 */
    @Min(value = 1, message = "每页数量最小为 1")
    private Integer pageSize = 10;
}
