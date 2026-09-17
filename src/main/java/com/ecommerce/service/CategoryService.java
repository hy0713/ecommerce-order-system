package com.ecommerce.service;

import com.ecommerce.dto.CategoryDTO;
import com.ecommerce.vo.CategoryVO;

import java.util.List;

/**
 * 商品分类服务
 */
public interface CategoryService {

    /**
     * 分类树查询（全部启用状态分类）
     */
    List<CategoryVO> tree();

    /**
     * 新增分类
     */
    Long add(CategoryDTO categoryDTO);

    /**
     * 修改分类
     */
    void update(Long id, CategoryDTO categoryDTO);

    /**
     * 删除分类（存在子分类时禁止删除）
     */
    void delete(Long id);
}
