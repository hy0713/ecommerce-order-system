package com.ecommerce.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecommerce.common.dto.ProductDTO;
import com.ecommerce.common.dto.ProductQueryDTO;
import com.ecommerce.common.feign.StockDeductDTO;
import com.ecommerce.common.feign.StockDeductResult;
import com.ecommerce.common.vo.ProductVO;

import java.util.List;

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
     * 商品实时详情（直查数据库，供加购/下单等业务校验使用，不读缓存）
     */
    ProductVO detailFresh(Long id);

    /**
     * 批量查询商品（购物车列表用）
     */
    List<ProductVO> listByIds(List<Long> ids);

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
     * 扣减库存（Redisson 分布式锁 + 乐观锁双层保障，防超卖）
     */
    StockDeductResult deductStock(StockDeductDTO dto);

    /**
     * 回补库存（取消订单 / 超时取消 / 下单失败补偿）
     */
    void restoreStock(Long productId, Integer quantity);
}
