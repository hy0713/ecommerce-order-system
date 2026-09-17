package com.ecommerce.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品表
 */
@Data
@TableName("product_info")
public class ProductInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 商品ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属分类ID */
    private Long categoryId;

    /** 商品名称 */
    private String name;

    /** 商品单价 */
    private BigDecimal price;

    /** 库存数量 */
    private Integer stock;

    /** 商品描述 */
    private String description;

    /** 商品主图地址 */
    private String icon;

    /** 状态：0 下架 1 上架 */
    private Integer status;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
