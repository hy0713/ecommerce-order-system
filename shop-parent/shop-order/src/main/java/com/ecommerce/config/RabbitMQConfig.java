package com.ecommerce.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 延迟队列配置（订单超时自动取消）
 * 采用「TTL + 死信队列」实现延迟消息（不依赖 delayed_message_exchange 插件）：
 * 下单消息进入等待队列（队列级 TTL = 订单过期时长），到期后自动转入死信队列由消费者取消订单。
 *
 * <p>消费链路的失败兜底：
 * <pre>
 *   order.exchange ──order.wait──▶ order.wait.queue (TTL)
 *                                       │ 到期死信
 *                                       ▼
 *                                  order.dlx.exchange ──order.delay──▶ order.delay.queue（消费者）
 *                                       │ 重试耗尽后死信
 *                                       ▼
 *                                  order.fail.exchange ──order.timeout.failed──▶ order.timeout.failed.queue（人工排查）
 * </pre>
 * 历史缺陷：order.delay.queue 自身没有配置死信交换机，配合 spring.rabbitmq
 * {@code default-requeue-rejected=false}，消费异常时消息会被直接丢弃、无任何留痕。
 */
@Configuration
public class RabbitMQConfig {

    /** 订单交换机 */
    public static final String ORDER_EXCHANGE = "order.exchange";

    /** 等待队列（设置 TTL，到期后投递死信） */
    public static final String WAIT_QUEUE = "order.wait.queue";

    /** 死信交换机 */
    public static final String DLX_EXCHANGE = "order.dlx.exchange";

    /** 消费者队列（接收死信消息，执行取消） */
    public static final String DELAY_QUEUE = "order.delay.queue";

    /** 失败消息落地交换机（重试耗尽后的最终归档） */
    public static final String FAIL_EXCHANGE = "order.fail.exchange";

    /** 失败消息落地队列（人工排查与补偿取数） */
    public static final String FAIL_QUEUE = "order.timeout.failed.queue";

    /** 死信路由键 */
    public static final String DLX_ROUTING_KEY = "order.delay";

    /** 等待队列路由键 */
    public static final String WAIT_ROUTING_KEY = "order.wait";

    /** 失败路由键 */
    public static final String FAIL_ROUTING_KEY = "order.timeout.failed";

    /** 订单超时分钟数 */
    @Value("${ecommerce.order.expire-minutes:30}")
    private double expireMinutes;

    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(ORDER_EXCHANGE, true, false);
    }

    /**
     * 等待队列：消息 TTL 为订单过期时长，过期后进入死信交换机
     */
    @Bean
    public Queue waitQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", (long) (expireMinutes * 60 * 1000));
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", DLX_ROUTING_KEY);
        return QueueBuilder.durable(WAIT_QUEUE).withArguments(args).build();
    }

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    /**
     * 消费者队列：重试耗尽后死信到失败队列，保证消息不静默丢失
     */
    @Bean
    public Queue delayQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", FAIL_EXCHANGE);
        args.put("x-dead-letter-routing-key", FAIL_ROUTING_KEY);
        return QueueBuilder.durable(DELAY_QUEUE).withArguments(args).build();
    }

    @Bean
    public DirectExchange failExchange() {
        return new DirectExchange(FAIL_EXCHANGE, true, false);
    }

    @Bean
    public Queue failQueue() {
        return new Queue(FAIL_QUEUE, true);
    }

    @Bean
    public Binding waitBinding() {
        return BindingBuilder.bind(waitQueue()).to(orderExchange()).with(WAIT_ROUTING_KEY);
    }

    @Bean
    public Binding dlxBinding() {
        return BindingBuilder.bind(delayQueue()).to(dlxExchange()).with(DLX_ROUTING_KEY);
    }

    @Bean
    public Binding failBinding() {
        return BindingBuilder.bind(failQueue()).to(failExchange()).with(FAIL_ROUTING_KEY);
    }
}
