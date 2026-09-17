package com.ecommerce.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.common.entity.OrderMaster;
import com.ecommerce.common.enums.OrderStatusEnum;
import com.ecommerce.mapper.OrderMasterMapper;
import com.ecommerce.service.OrderService;
import com.ecommerce.service.StockCompensationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 兜底任务：
 * <ol>
 *   <li>订单超时扫描 —— 延迟消息的兜底（消息发送失败 / 消费失败 / 服务重启等场景）</li>
 *   <li>库存补偿重试 —— 回补失败的库存最终一致保证</li>
 * </ol>
 */
@Slf4j
@Component
public class OrderTimeoutScheduler {

    /** 单轮扫描的订单上限，避免积压时一次性载入全部订单导致内存溢出 */
    private static final int SCAN_BATCH_SIZE = 200;

    /** 单轮补偿重试条数 */
    private static final int COMPENSATION_BATCH_SIZE = 100;

    private final OrderMasterMapper orderMasterMapper;
    private final OrderService orderService;
    private final StockCompensationService compensationService;

    @Value("${ecommerce.order.expire-minutes:30}")
    private double expireMinutes;

    public OrderTimeoutScheduler(OrderMasterMapper orderMasterMapper,
                                 OrderService orderService,
                                 StockCompensationService compensationService) {
        this.orderMasterMapper = orderMasterMapper;
        this.orderService = orderService;
        this.compensationService = compensationService;
    }

    /**
     * 每 30 秒扫描一次超时待支付订单（分批，最多 SCAN_BATCH_SIZE 条）
     */
    @Scheduled(fixedDelay = 30_000)
    public void scanExpiredOrders() {
        // expireMinutes 单位是分钟：精确换算为秒（0.25 → 15 秒，验收测试用）
        LocalDateTime expiredBefore = LocalDateTime.now().minusSeconds((long) (expireMinutes * 60));
        List<OrderMaster> expiredOrders = orderMasterMapper.selectList(new LambdaQueryWrapper<OrderMaster>()
                .eq(OrderMaster::getOrderStatus, OrderStatusEnum.WAIT_PAY.getCode())
                .lt(OrderMaster::getCreateTime, expiredBefore)
                .orderByAsc(OrderMaster::getCreateTime)
                .last("LIMIT " + SCAN_BATCH_SIZE));
        if (expiredOrders.isEmpty()) {
            return;
        }
        log.info("定时兜底扫描发现 {} 笔超时待支付订单，开始取消", expiredOrders.size());
        for (OrderMaster order : expiredOrders) {
            try {
                orderService.cancelByTimeout(order.getOrderNo());
            } catch (Exception e) {
                log.error("定时兜底取消订单异常：orderNo={}", order.getOrderNo(), e);
            }
        }
    }

    /**
     * 每 60 秒重试一次待处理的库存补偿任务。
     * 这是「回补失败」不再丢账的最后一环：补偿流水落库后由本任务持续重试至成功或超次数上限。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void retryStockCompensations() {
        try {
            compensationService.retryPending(COMPENSATION_BATCH_SIZE);
        } catch (Exception e) {
            log.error("库存补偿重试任务异常", e);
        }
    }
}
