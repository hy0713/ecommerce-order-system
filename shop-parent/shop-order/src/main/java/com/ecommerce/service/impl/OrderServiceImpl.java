package com.ecommerce.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecommerce.common.dto.OrderCreateDTO;
import com.ecommerce.common.entity.OrderDetail;
import com.ecommerce.common.entity.OrderMaster;
import com.ecommerce.common.entity.ShoppingCart;
import com.ecommerce.common.entity.StockCompensation;
import com.ecommerce.common.entity.UserAddress;
import com.ecommerce.common.enums.OrderStatusEnum;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.feign.ProductFeignClient;
import com.ecommerce.common.feign.StockDeductDTO;
import com.ecommerce.common.feign.StockDeductResult;
import com.ecommerce.common.feign.UserFeignClient;
import com.ecommerce.common.result.Result;
import com.ecommerce.common.result.ResultCode;
import com.ecommerce.common.util.SnowflakeUtil;
import com.ecommerce.common.vo.OrderItemVO;
import com.ecommerce.common.vo.OrderVO;
import com.ecommerce.config.RabbitMQConfig;
import com.ecommerce.mapper.OrderDetailMapper;
import com.ecommerce.mapper.OrderMasterMapper;
import com.ecommerce.mapper.ShoppingCartMapper;
import com.ecommerce.service.CartService;
import com.ecommerce.service.OrderService;
import com.ecommerce.service.StockCompensationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 订单服务实现
 * 库存扣减/回补、商品查询、地址校验均通过 Feign 跨服务调用；
 * 下单跨服务无强一致事务，采用「失败补偿 + 补偿流水落库重试」保证最终一致。
 *
 * <p><b>并发正确性约定（改动前必读）</b>：订单状态流转一律使用
 * {@code UPDATE ... WHERE id=? AND order_status=?} 条件更新并校验受影响行数，
 * 由数据库决定唯一赢家。禁止使用「先 select 判状态、再无条件 updateById」的写法——
 * 那会让并发的两次取消同时通过校验，导致库存被回补两次。
 */
@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    private final OrderMasterMapper orderMasterMapper;
    private final OrderDetailMapper orderDetailMapper;
    private final ShoppingCartMapper cartMapper;
    private final CartService cartService;
    private final ProductFeignClient productFeignClient;
    private final UserFeignClient userFeignClient;
    private final RabbitTemplate rabbitTemplate;
    private final StockCompensationService compensationService;

    @Value("${ecommerce.order.expire-minutes:30}")
    private double expireMinutes;

    public OrderServiceImpl(OrderMasterMapper orderMasterMapper,
                            OrderDetailMapper orderDetailMapper,
                            ShoppingCartMapper cartMapper,
                            CartService cartService,
                            ProductFeignClient productFeignClient,
                            UserFeignClient userFeignClient,
                            RabbitTemplate rabbitTemplate,
                            StockCompensationService compensationService) {
        this.orderMasterMapper = orderMasterMapper;
        this.orderDetailMapper = orderDetailMapper;
        this.cartMapper = cartMapper;
        this.cartService = cartService;
        this.productFeignClient = productFeignClient;
        this.userFeignClient = userFeignClient;
        this.rabbitTemplate = rabbitTemplate;
        this.compensationService = compensationService;
    }

    /**
     * 下单全链路（跨服务）：
     * Feign 校验地址 → 校验购物车 → 逐个 Feign 扣库存 → 本地建单 → 清购物车 → 发送延迟取消消息
     * 任一环节失败：已扣商品逐个回补（失败则登记补偿流水），本地事务回滚
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO create(Long userId, OrderCreateDTO orderCreateDTO) {
        // 0. 先生成订单号：补偿流水需要可追溯的订单号，故提前生成
        String orderNo = SnowflakeUtil.nextIdStr();

        // 1. Feign 校验收货地址合法性（归属校验在用户服务）
        Result<UserAddress> addressResult = userFeignClient.getAddress(orderCreateDTO.getAddressId());
        checkFeignResult(addressResult);
        UserAddress address = addressResult.getData();

        // 2. 校验购物车选中商品非空
        List<ShoppingCart> selectedItems = cartService.listSelected(userId);
        if (selectedItems.isEmpty()) {
            throw new BusinessException(ResultCode.CART_EMPTY);
        }

        // 3. 逐个 Feign 扣减库存（商品服务内 Redisson 锁 + 行锁/乐观锁），失败触发补偿
        List<OrderItemSnapshot> snapshots = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        try {
            for (ShoppingCart item : selectedItems) {
                StockDeductResult deduct = deductStock(item);
                snapshots.add(new OrderItemSnapshot(item.getProductId(), deduct.getProductName(),
                        deduct.getPrice(), item.getQuantity()));
                totalAmount = totalAmount.add(deduct.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            }
        } catch (Exception e) {
            // 5. 补偿：回补已扣减的商品库存；回补失败的登记补偿流水（独立事务，不会被本事务回滚）
            compensateOnCreateFail(orderNo, snapshots);
            throw e;
        }

        // 4. 生成订单：保存商品快照
        OrderMaster orderMaster = new OrderMaster();
        orderMaster.setOrderNo(orderNo);
        orderMaster.setUserId(userId);
        orderMaster.setTotalAmount(totalAmount);
        orderMaster.setOrderStatus(OrderStatusEnum.WAIT_PAY.getCode());
        orderMaster.setReceiverName(address.getReceiverName());
        orderMaster.setReceiverPhone(address.getReceiverPhone());
        orderMaster.setReceiverAddress(address.getAddress());
        orderMasterMapper.insert(orderMaster);

        for (OrderItemSnapshot snapshot : snapshots) {
            OrderDetail detail = new OrderDetail();
            detail.setOrderNo(orderNo);
            detail.setProductId(snapshot.getProductId());
            detail.setProductName(snapshot.getProductName());
            detail.setProductPrice(snapshot.getProductPrice());
            detail.setProductQuantity(snapshot.getQuantity());
            orderDetailMapper.insert(detail);
        }

        // 清空购物车中已结算的选中商品（限定 user_id，避免越权删除他人购物车）
        List<Long> cartItemIds = selectedItems.stream().map(ShoppingCart::getId).collect(Collectors.toList());
        cartMapper.delete(new LambdaQueryWrapper<ShoppingCart>()
                .eq(ShoppingCart::getUserId, userId)
                .in(ShoppingCart::getId, cartItemIds));

        // 6. 事务提交后发送延迟消息：订单超时自动取消
        runAfterCommit(() -> sendDelayCancelMessage(orderNo));

        log.info("订单创建成功：orderNo={}, userId={}, totalAmount={}", orderNo, userId, totalAmount);
        return toOrderVO(orderMaster, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO pay(Long userId, Long orderId) {
        OrderMaster order = getOwnedOrder(userId, orderId);
        if (!transitionStatus(orderId, OrderStatusEnum.WAIT_PAY, OrderStatusEnum.PAID)) {
            throw new BusinessException(ResultCode.ORDER_CONCURRENT_MODIFY);
        }
        orderMasterMapper.update(null, new LambdaUpdateWrapper<OrderMaster>()
                .eq(OrderMaster::getId, orderId)
                .set(OrderMaster::getPayTime, LocalDateTime.now()));
        order.setOrderStatus(OrderStatusEnum.PAID.getCode());
        order.setPayTime(LocalDateTime.now());
        return toOrderVO(order, null);
    }

    /**
     * 取消订单：仅待支付可取消（否则抛 6002）；条件更新成功后异步回补库存
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO cancel(Long userId, Long orderId) {
        OrderMaster order = getOwnedOrder(userId, orderId);
        checkStatus(order, OrderStatusEnum.WAIT_PAY);
        if (!transitionStatus(orderId, OrderStatusEnum.WAIT_PAY, OrderStatusEnum.CANCELED)) {
            // 并发下已被其他请求（用户取消 / 超时取消）抢先处理，避免重复回补
            throw new BusinessException(ResultCode.ORDER_CONCURRENT_MODIFY);
        }
        order.setOrderStatus(OrderStatusEnum.CANCELED.getCode());
        // 状态已提交后再回补，回补失败登记补偿流水，不阻塞取消结果
        runAfterCommit(() -> restoreOrderStock(order.getOrderNo(), StockCompensation.BIZ_ORDER_CANCEL));
        return toOrderVO(order, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO ship(Long orderId) {
        OrderMaster order = getById(orderId);
        if (!transitionStatus(orderId, OrderStatusEnum.PAID, OrderStatusEnum.SHIPPED)) {
            throw new BusinessException(ResultCode.ORDER_CONCURRENT_MODIFY);
        }
        order.setOrderStatus(OrderStatusEnum.SHIPPED.getCode());
        return toOrderVO(order, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO complete(Long orderId) {
        OrderMaster order = getById(orderId);
        if (!transitionStatus(orderId, OrderStatusEnum.SHIPPED, OrderStatusEnum.FINISHED)) {
            throw new BusinessException(ResultCode.ORDER_CONCURRENT_MODIFY);
        }
        order.setOrderStatus(OrderStatusEnum.FINISHED.getCode());
        return toOrderVO(order, null);
    }

    /**
     * 超时自动取消（延迟消息消费 / 定时任务兜底共用）。
     *
     * <p>幂等由「条件更新」保证：只有把状态从待支付改为已取消的那一次调用会返回 1 行，
     * 该调用者才是唯一赢家并执行回补；其余调用返回 0 行直接退出。
     * 因此不再依赖 Redis SETNX —— 历史实现把幂等键写在事务外，回滚后键仍存活，
     * 反而会挡住定时任务的兜底重试。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelByTimeout(String orderNo) {
        int rows = orderMasterMapper.update(null, new LambdaUpdateWrapper<OrderMaster>()
                .eq(OrderMaster::getOrderNo, orderNo)
                .eq(OrderMaster::getOrderStatus, OrderStatusEnum.WAIT_PAY.getCode())
                .set(OrderMaster::getOrderStatus, OrderStatusEnum.CANCELED.getCode()));
        if (rows == 0) {
            // 订单不存在 / 已支付 / 已被取消 —— 正常情况下都应静默跳过
            log.info("订单无需超时取消（不存在或非待支付）：orderNo={}", orderNo);
            return;
        }
        log.info("订单超时取消成功，回补库存将在状态提交后执行：orderNo={}", orderNo);
        // 与 cancel()/adminCancel() 保持同一模式：状态提交后再回补，
        // 避免在事务内回补失败把「已取消」一起回滚
        runAfterCommit(() -> restoreOrderStock(orderNo, StockCompensation.BIZ_ORDER_CANCEL));
    }

    /**
     * 管理端取消订单：不做归属校验（调用方须已通过管理员校验），仅待支付可取消
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO adminCancel(Long orderId) {
        OrderMaster order = getById(orderId);
        checkStatus(order, OrderStatusEnum.WAIT_PAY);
        if (!transitionStatus(orderId, OrderStatusEnum.WAIT_PAY, OrderStatusEnum.CANCELED)) {
            throw new BusinessException(ResultCode.ORDER_CONCURRENT_MODIFY);
        }
        order.setOrderStatus(OrderStatusEnum.CANCELED.getCode());
        runAfterCommit(() -> restoreOrderStock(order.getOrderNo(), StockCompensation.BIZ_ORDER_CANCEL));
        return toOrderVO(order, null);
    }

    @Override
    public Page<OrderVO> page(Long userId, Integer orderStatus, int pageNum, int pageSize) {
        // userId 为 null 表示管理端查询全站订单（不追加 user_id 过滤条件）
        LambdaQueryWrapper<OrderMaster> wrapper = new LambdaQueryWrapper<OrderMaster>()
                .eq(userId != null, OrderMaster::getUserId, userId)
                .eq(orderStatus != null, OrderMaster::getOrderStatus, orderStatus)
                .orderByDesc(OrderMaster::getCreateTime);
        Page<OrderMaster> page = orderMasterMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);

        // 批量查询明细，避免逐条订单再查一次明细（N+1）
        Map<String, List<OrderDetail>> detailMap = loadDetails(
                page.getRecords().stream().map(OrderMaster::getOrderNo).collect(Collectors.toList()));

        Page<OrderVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream()
                .map(o -> toOrderVO(o, detailMap.get(o.getOrderNo())))
                .collect(Collectors.toList()));
        return voPage;
    }

    @Override
    public OrderVO detail(Long userId, Long orderId) {
        OrderMaster order = getOwnedOrder(userId, orderId);
        return toOrderVO(order, null);
    }

    /**
     * 订单统计（数据概览页）。金额由 SQL 聚合，避免把全表金额拉进 JVM 求和。
     */
    @Override
    public Map<String, Object> stats() {
        int canceled = OrderStatusEnum.CANCELED.getCode();
        LocalDateTime todayStart = LocalDateTime.now().toLocalDate().atStartOfDay();

        Map<String, Object> result = new HashMap<>();
        result.put("totalOrders", orderMasterMapper.selectCount(
                new LambdaQueryWrapper<OrderMaster>().ne(OrderMaster::getOrderStatus, canceled)));
        result.put("totalSales", sumAmount(new QueryWrapper<OrderMaster>()
                .select("IFNULL(SUM(total_amount), 0)")
                .ne("order_status", canceled)));
        result.put("todayOrders", orderMasterMapper.selectCount(new LambdaQueryWrapper<OrderMaster>()
                .ne(OrderMaster::getOrderStatus, canceled)
                .ge(OrderMaster::getCreateTime, todayStart)));
        result.put("todaySales", sumAmount(new QueryWrapper<OrderMaster>()
                .select("IFNULL(SUM(total_amount), 0)")
                .ne("order_status", canceled)
                .ge("create_time", todayStart)));
        result.put("pendingCompensations", compensationService.pendingCount());
        return result;
    }

    /**
     * 执行库存回补：逐条商品调用商品服务，失败则登记补偿流水由定时任务重试
     */
    private void restoreOrderStock(String orderNo, String bizType) {
        List<OrderDetail> details = orderDetailMapper.selectList(new LambdaQueryWrapper<OrderDetail>()
                .eq(OrderDetail::getOrderNo, orderNo));
        if (details.isEmpty()) {
            log.warn("订单无明细，跳过库存回补：orderNo={}", orderNo);
            return;
        }
        for (OrderDetail detail : details) {
            compensationService.restoreOrRecord(orderNo, detail.getProductId(),
                    detail.getProductQuantity(), bizType);
        }
        log.info("订单库存回补流程结束：orderNo={}, 明细 {} 条", orderNo, details.size());
    }

    /**
     * 下单失败补偿：回补已扣减商品库存
     */
    private void compensateOnCreateFail(String orderNo, List<OrderItemSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }
        log.warn("下单失败，开始补偿回补已扣库存：orderNo={}, 商品数={}", orderNo, snapshots.size());
        for (OrderItemSnapshot snapshot : snapshots) {
            compensationService.restoreOrRecord(orderNo, snapshot.getProductId(),
                    snapshot.getQuantity(), StockCompensation.BIZ_CREATE_FAIL);
        }
    }

    /**
     * 条件更新订单状态，返回是否更新成功（唯一赢家判定）。
     */
    private boolean transitionStatus(Long orderId, OrderStatusEnum from, OrderStatusEnum to) {
        int rows = orderMasterMapper.update(null, new LambdaUpdateWrapper<OrderMaster>()
                .eq(OrderMaster::getId, orderId)
                .eq(OrderMaster::getOrderStatus, from.getCode())
                .set(OrderMaster::getOrderStatus, to.getCode()));
        if (rows == 0) {
            log.info("订单状态条件更新未命中：orderId={}, expect={}, target={}", orderId, from, to);
        }
        return rows > 0;
    }

    /**
     * Feign 扣减库存，校验返回结果
     */
    private StockDeductResult deductStock(ShoppingCart item) {
        StockDeductDTO dto = new StockDeductDTO();
        dto.setProductId(item.getProductId());
        dto.setQuantity(item.getQuantity());
        Result<StockDeductResult> result = productFeignClient.deductStock(dto);
        checkFeignResult(result);
        StockDeductResult deduct = result.getData();
        if (deduct == null || !deduct.isSuccess()) {
            throw new BusinessException(deduct == null ? ResultCode.ERROR.getCode() : deduct.getCode(),
                    deduct == null ? "库存扣减失败" : deduct.getMessage());
        }
        return deduct;
    }

    /**
     * 发送延迟取消消息：进入等待队列，TTL 到期后经死信队列投递消费者
     */
    private void sendDelayCancelMessage(String orderNo) {
        try {
            rabbitTemplate.convertAndSend(RabbitMQConfig.ORDER_EXCHANGE, RabbitMQConfig.WAIT_ROUTING_KEY, orderNo);
            log.info("订单超时取消延迟消息已发送：orderNo={}, expireMinutes={}", orderNo, expireMinutes);
        } catch (Exception e) {
            // 消息发送失败不影响下单结果，由定时任务兜底扫描
            log.error("订单超时取消延迟消息发送失败，将由定时任务兜底：orderNo={}", orderNo, e);
        }
    }

    /**
     * Feign 返回结果统一校验：code != 200 时转换为业务异常
     */
    private void checkFeignResult(Result<?> result) {
        if (result == null) {
            throw new BusinessException(ResultCode.ERROR, "服务调用异常");
        }
        if (!result.isSuccess()) {
            throw new BusinessException(result.getCode(), result.getMessage());
        }
    }

    /** 聚合查询订单金额（SQL 层 SUM） */
    private BigDecimal sumAmount(QueryWrapper<OrderMaster> wrapper) {
        List<Object> objs = orderMasterMapper.selectObjs(wrapper);
        if (objs == null || objs.isEmpty() || objs.get(0) == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(objs.get(0).toString());
    }

    /** 批量加载订单明细，按订单号分组 */
    private Map<String, List<OrderDetail>> loadDetails(List<String> orderNos) {
        if (orderNos == null || orderNos.isEmpty()) {
            return Collections.emptyMap();
        }
        return orderDetailMapper.selectList(new LambdaQueryWrapper<OrderDetail>()
                        .in(OrderDetail::getOrderNo, orderNos))
                .stream()
                .collect(Collectors.groupingBy(OrderDetail::getOrderNo));
    }

    /** 注册事务提交后回调；无事务时直接执行 */
    private void runAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    private OrderVO toOrderVO(OrderMaster order, List<OrderDetail> presetDetails) {
        OrderVO vo = BeanUtil.copyProperties(order, OrderVO.class);
        OrderStatusEnum statusEnum = OrderStatusEnum.of(order.getOrderStatus());
        vo.setOrderStatusDesc(statusEnum == null ? "未知状态" : statusEnum.getDesc());
        List<OrderDetail> details = presetDetails != null ? presetDetails
                : orderDetailMapper.selectList(new LambdaQueryWrapper<OrderDetail>()
                        .eq(OrderDetail::getOrderNo, order.getOrderNo()));
        List<OrderItemVO> items = details.stream().map(d -> {
            OrderItemVO item = new OrderItemVO();
            item.setProductId(d.getProductId());
            item.setProductName(d.getProductName());
            item.setProductPrice(d.getProductPrice());
            item.setProductQuantity(d.getProductQuantity());
            return item;
        }).collect(Collectors.toList());
        vo.setItems(items);
        return vo;
    }

    private OrderMaster getOwnedOrder(Long userId, Long orderId) {
        OrderMaster order = getById(orderId);
        // userId 为 null 表示管理端视角，跳过归属校验（调用方必须已通过 requireAdmin）。
        // 管理端能看到全站订单，点进详情自然也要能打开，否则列表可看、详情打不开。
        if (userId != null && !Objects.equals(order.getUserId(), userId)) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    private OrderMaster getById(Long orderId) {
        OrderMaster order = orderMasterMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    private void checkStatus(OrderMaster order, OrderStatusEnum expected) {
        if (order.getOrderStatus() == null || order.getOrderStatus() != expected.getCode()) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR);
        }
    }

    /** 下单过程中的商品快照 */
    private static class OrderItemSnapshot {
        private final Long productId;
        private final String productName;
        private final BigDecimal productPrice;
        private final Integer quantity;

        OrderItemSnapshot(Long productId, String productName, BigDecimal productPrice, Integer quantity) {
            this.productId = productId;
            this.productName = productName;
            this.productPrice = productPrice;
            this.quantity = quantity;
        }

        public Long getProductId() {
            return productId;
        }

        public String getProductName() {
            return productName;
        }

        public BigDecimal getProductPrice() {
            return productPrice;
        }

        public Integer getQuantity() {
            return quantity;
        }
    }
}
