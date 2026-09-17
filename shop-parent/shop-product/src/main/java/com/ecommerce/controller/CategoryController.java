package com.ecommerce.controller;

import com.ecommerce.common.dto.CategoryDTO;
import com.ecommerce.common.result.Result;
import com.ecommerce.common.util.UserContext;
import com.ecommerce.common.vo.CategoryVO;
import com.ecommerce.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品分类接口
 */
@Tag(name = "商品分类管理")
@RestController
@RequestMapping("/api/category")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @Operation(summary = "分类树查询")
    @GetMapping("/tree")
    public Result<List<CategoryVO>> tree() {
        return Result.success(categoryService.tree());
    }

    @Operation(summary = "新增分类（管理员）")
    @PostMapping
    public Result<Long> add(@Validated @RequestBody CategoryDTO categoryDTO) {
        UserContext.requireAdmin();
        return Result.success(categoryService.add(categoryDTO));
    }

    @Operation(summary = "修改分类（管理员）")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Validated @RequestBody CategoryDTO categoryDTO) {
        UserContext.requireAdmin();
        categoryService.update(id, categoryDTO);
        return Result.success();
    }

    @Operation(summary = "删除分类（管理员）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        UserContext.requireAdmin();
        categoryService.delete(id);
        return Result.success();
    }
}
