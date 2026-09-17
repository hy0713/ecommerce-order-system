package com.ecommerce.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ResultCode;
import com.ecommerce.dto.CategoryDTO;
import com.ecommerce.entity.ProductCategory;
import com.ecommerce.mapper.ProductCategoryMapper;
import com.ecommerce.service.CategoryService;
import com.ecommerce.vo.CategoryVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 商品分类服务实现
 */
@Service
public class CategoryServiceImpl implements CategoryService {

    private final ProductCategoryMapper categoryMapper;

    public CategoryServiceImpl(ProductCategoryMapper categoryMapper) {
        this.categoryMapper = categoryMapper;
    }

    @Override
    public List<CategoryVO> tree() {
        List<ProductCategory> all = categoryMapper.selectList(new LambdaQueryWrapper<ProductCategory>()
                .eq(ProductCategory::getStatus, 1)
                .orderByAsc(ProductCategory::getSort)
                .orderByAsc(ProductCategory::getId));
        if (all.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, List<CategoryVO>> childrenMap = all.stream()
                .map(c -> BeanUtil.copyProperties(c, CategoryVO.class))
                .collect(Collectors.groupingBy(CategoryVO::getParentId));
        List<CategoryVO> roots = childrenMap.getOrDefault(0L, new ArrayList<>());
        attachChildren(roots, childrenMap);
        return roots;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long add(CategoryDTO categoryDTO) {
        ProductCategory category = new ProductCategory();
        category.setName(categoryDTO.getName());
        // 新增：不传则默认一级分类 / 排序 0（DTO 已不再给字段预设初值）
        category.setParentId(categoryDTO.getParentId() == null ? 0L : categoryDTO.getParentId());
        category.setSort(categoryDTO.getSort() == null ? 0 : categoryDTO.getSort());
        category.setStatus(categoryDTO.getStatus() == null ? 1 : categoryDTO.getStatus());
        categoryMapper.insert(category);
        return category.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, CategoryDTO categoryDTO) {
        ProductCategory category = getById(id);
        category.setName(categoryDTO.getName());
        // 只更新显式传了的字段。此前无条件 setParentId/setSort，而 DTO 字段带初值恒非 null，
        // 导致 PUT {"name":"x"} 会把子分类静默提升为一级、排序归零（数据损坏）。
        if (categoryDTO.getParentId() != null) {
            category.setParentId(categoryDTO.getParentId());
        }
        if (categoryDTO.getSort() != null) {
            category.setSort(categoryDTO.getSort());
        }
        if (categoryDTO.getStatus() != null) {
            category.setStatus(categoryDTO.getStatus());
        }
        categoryMapper.updateById(category);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        getById(id);
        Long childrenCount = categoryMapper.selectCount(new LambdaQueryWrapper<ProductCategory>()
                .eq(ProductCategory::getParentId, id));
        if (childrenCount != null && childrenCount > 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "存在子分类，无法删除");
        }
        categoryMapper.deleteById(id);
    }

    private ProductCategory getById(Long id) {
        ProductCategory category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException(ResultCode.CATEGORY_NOT_FOUND);
        }
        return category;
    }

    private void attachChildren(List<CategoryVO> nodes, Map<Long, List<CategoryVO>> childrenMap) {
        for (CategoryVO node : nodes) {
            List<CategoryVO> children = childrenMap.getOrDefault(node.getId(), new ArrayList<>());
            attachChildren(children, childrenMap);
            node.setChildren(children.isEmpty() ? null : children);
        }
    }
}
