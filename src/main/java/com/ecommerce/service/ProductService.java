package com.ecommerce.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecommerce.dto.ProductDTO;
import com.ecommerce.dto.ProductQueryDTO;
import com.ecommerce.vo.ProductVO;

/**
 * 商品服务
 */
public interface ProductService {

    /**
     * 商品分页查询（分类 / 价格区间 / 关键词模糊）
     */
    Page<ProductVO> page(ProductQueryDTO queryDTO);

    /**
     * 商品详情（Redis 缓存 30 分钟，空值缓存防穿透）
     */
    ProductVO detail(Long id);

    /**
     * 新增商品
     */
    Long add(ProductDTO productDTO);

    /**
     * 修改商品
     */
    void update(Long id, ProductDTO productDTO);

    /**
     * 上下架
     */
    void updateStatus(Long id, Integer status);

    /**
     * 删除商品
     */
    void delete(Long id);

    /**
     * 清除商品详情缓存。
     *
     * <p>供订单侧在「扣减 / 回补库存」后调用：库存走的是自定义 SQL（不经过本类的
     * update 链路），若不显式清缓存，商品详情会带着旧库存最长存在一个过期周期，
     * 与列表页（直连 DB）自相矛盾。
     */
    void evictDetailCache(Long productId);
}
