package com.ecommerce.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 分类新增 / 修改入参
 */
@Data
public class CategoryDTO {

    @NotBlank(message = "分类名称不能为空")
    @Size(max = 64, message = "分类名称长度不能超过 64 位")
    private String name;

    /**
     * 父分类ID，0 为一级分类。
     * 新增时不传按 0（一级分类）处理；<b>修改时不传表示保持不变</b>，不会把子分类提升为一级。
     */
    private Long parentId;

    /** 排序权重，数值越小越靠前。不传表示保持不变 */
    private Integer sort;

    @Min(value = 0, message = "分类状态参数错误")
    @Max(value = 1, message = "分类状态参数错误")
    private Integer status;
}
