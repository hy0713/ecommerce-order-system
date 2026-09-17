package com.ecommerce.mq;

import com.ecommerce.config.RabbitMQConfig;
import com.ecommerce.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 订单超时取消消费者
 * 接收延迟队列消息（订单号），订单仍为待支付则取消并回补库存（幂等）
 */
@Slf4j
@Component
public class OrderTimeoutConsumer {

    private final OrderService orderService;

    public OrderTimeoutConsumer(OrderService orderService) {
        this.orderService = orderService;
    }

    @RabbitListener(queues = RabbitMQConfig.DELAY_QUEUE)
    public void onOrderTimeout(String orderNo) {
        log.info("收到订单超时取消消息：orderNo={}", orderNo);
        orderService.cancelByTimeout(orderNo);
    }
}
