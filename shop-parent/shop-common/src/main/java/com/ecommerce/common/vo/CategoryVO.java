package com.ecommerce.common.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分类树视图
 */
@Data
public class CategoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String name;

    private Long parentId;

    /** 排序权重 */
    private Integer sort;

    /** 状态：0 禁用 1 启用 */
    private Integer status;

    /** 子分类 */
    private List<CategoryVO> children;
}
