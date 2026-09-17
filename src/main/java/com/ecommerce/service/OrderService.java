package com.ecommerce.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecommerce.dto.OrderCreateDTO;
import com.ecommerce.vo.OrderVO;

/**
 * 订单服务
 */
public interface OrderService {

    /**
     * 创建订单：校验 → 乐观锁扣库存 → 生成订单 → 清空购物车（整体事务）
     */
    OrderVO create(Long userId, OrderCreateDTO orderCreateDTO);

    /**
     * 模拟支付：待支付 → 已支付
     */
    OrderVO pay(Long userId, Long orderId);

    /**
     * 取消订单：仅待支付可取消，取消后回补库存
     */
    OrderVO cancel(Long userId, Long orderId);

    /**
     * 发货：已支付 → 已发货
     */
    OrderVO ship(Long orderId);

    /**
     * 完成：已发货 → 已完成
     */
    OrderVO complete(Long orderId);

    /**
     * 订单分页列表（当前用户 + 可选状态过滤）
     */
    Page<OrderVO> page(Long userId, Integer orderStatus, int pageNum, int pageSize);

    /**
     * 订单详情（主表 + 明细）
     */
    OrderVO detail(Long userId, Long orderId);
}
