package com.ecommerce.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecommerce.common.dto.OrderCreateDTO;
import com.ecommerce.common.vo.OrderVO;

/**
 * 订单服务
 */
public interface OrderService {

    /**
     * 创建订单：Feign 扣库存 → 本地建单 → 发送超时取消延迟消息（失败自动补偿回补库存）
     */
    OrderVO create(Long userId, OrderCreateDTO orderCreateDTO);

    /**
     * 模拟支付：待支付 → 已支付
     */
    OrderVO pay(Long userId, Long orderId);

    /**
     * 取消订单：仅待支付可取消，取消后 Feign 回补库存
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

    /**
     * 超时自动取消（延迟消息消费 / 定时任务兜底共用）：
     * 仍为待支付则取消并 Feign 回补库存，幂等可重复调用
     */
    void cancelByTimeout(String orderNo);

    /**
     * 管理端取消订单（无归属校验，回补库存）
     */
    OrderVO adminCancel(Long orderId);

    /**
     * 订单统计（数据概览页）：总订单量 / 总销售额（排除已取消）/ 今日订单量 / 今日销售额
     */
    java.util.Map<String, Object> stats();
}
