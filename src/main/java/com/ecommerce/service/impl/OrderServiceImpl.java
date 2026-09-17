package com.ecommerce.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecommerce.common.enums.OrderStatusEnum;
import com.ecommerce.common.enums.ProductStatusEnum;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ResultCode;
import com.ecommerce.common.util.SnowflakeUtil;
import com.ecommerce.dto.OrderCreateDTO;
import com.ecommerce.entity.OrderDetail;
import com.ecommerce.entity.OrderMaster;
import com.ecommerce.entity.ProductInfo;
import com.ecommerce.entity.ShoppingCart;
import com.ecommerce.entity.UserAddress;
import com.ecommerce.mapper.OrderDetailMapper;
import com.ecommerce.mapper.OrderMasterMapper;
import com.ecommerce.mapper.ProductInfoMapper;
import com.ecommerce.mapper.ShoppingCartMapper;
import com.ecommerce.mapper.UserAddressMapper;
import com.ecommerce.service.CartService;
import com.ecommerce.service.OrderService;
import com.ecommerce.service.ProductService;
import com.ecommerce.vo.OrderItemVO;
import com.ecommerce.vo.OrderVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 订单服务实现。
 *
 * <p><b>并发正确性约定（改动前必读）</b>：
 * <ol>
 *   <li>订单状态流转一律使用 {@code UPDATE ... WHERE id=? AND order_status=?} 条件更新
 *       并校验受影响行数，由数据库决定唯一赢家。禁止「先 select 判状态、再无条件
 *       updateById」——并发的两次取消会同时通过校验，导致库存被回补两次。</li>
 *   <li>库存扣减依赖 {@code SELECT ... FOR UPDATE} 的行锁把「判断库存 → 扣减」串行化，
 *       本类方法在 {@code @Transactional} 内执行，行锁才会持续持有到事务结束。</li>
 * </ol>
 */
@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    private final OrderMasterMapper orderMasterMapper;
    private final OrderDetailMapper orderDetailMapper;
    private final ProductInfoMapper productInfoMapper;
    private final ShoppingCartMapper cartMapper;
    private final UserAddressMapper userAddressMapper;
    private final CartService cartService;
    /** 用于在扣减 / 回补库存后清除商品详情缓存（库存走自定义 SQL，不经过商品服务的更新链路） */
    private final ProductService productService;

    public OrderServiceImpl(OrderMasterMapper orderMasterMapper,
                            OrderDetailMapper orderDetailMapper,
                            ProductInfoMapper productInfoMapper,
                            ShoppingCartMapper cartMapper,
                            UserAddressMapper userAddressMapper,
                            CartService cartService,
                            ProductService productService) {
        this.orderMasterMapper = orderMasterMapper;
        this.orderDetailMapper = orderDetailMapper;
        this.productInfoMapper = productInfoMapper;
        this.cartMapper = cartMapper;
        this.userAddressMapper = userAddressMapper;
        this.cartService = cartService;
        this.productService = productService;
    }

    /**
     * 下单全链路（单库事务，保证原子性）
     * 参数校验 → 商品校验 → 行锁内扣库存 → 金额计算 → 生成订单 → 清空购物车
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO create(Long userId, OrderCreateDTO orderCreateDTO) {
        // 1. 校验收货地址合法性
        UserAddress address = userAddressMapper.selectById(orderCreateDTO.getAddressId());
        if (address == null || !Objects.equals(address.getUserId(), userId)) {
            throw new BusinessException(ResultCode.ADDRESS_NOT_FOUND);
        }

        // 2. 校验购物车选中商品非空
        List<ShoppingCart> selectedItems = cartService.listSelected(userId);
        if (selectedItems.isEmpty()) {
            throw new BusinessException(ResultCode.CART_EMPTY);
        }

        // 3+4. 逐个扣减库存，并保存扣减成功时的商品快照
        List<OrderItemSnapshot> snapshots = new java.util.ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (ShoppingCart item : selectedItems) {
            DeductOutcome outcome = deductStock(item);
            snapshots.add(new OrderItemSnapshot(outcome.product.getId(), outcome.product.getName(),
                    outcome.product.getPrice(), item.getQuantity()));
            totalAmount = totalAmount.add(
                    outcome.product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }

        // 5. 生成订单：雪花算法生成唯一订单号
        String orderNo = SnowflakeUtil.nextIdStr();
        OrderMaster orderMaster = new OrderMaster();
        orderMaster.setOrderNo(orderNo);
        orderMaster.setUserId(userId);
        orderMaster.setTotalAmount(totalAmount);
        orderMaster.setOrderStatus(OrderStatusEnum.WAIT_PAY.getCode());
        orderMaster.setReceiverName(address.getReceiverName());
        orderMaster.setReceiverPhone(address.getReceiverPhone());
        orderMaster.setReceiverAddress(address.getAddress());
        orderMasterMapper.insert(orderMaster);

        // 6. 保存商品快照到订单明细（复用扣减时已取到的商品对象，不再重复查库）
        for (OrderItemSnapshot snapshot : snapshots) {
            OrderDetail detail = new OrderDetail();
            detail.setOrderNo(orderNo);
            detail.setProductId(snapshot.productId());
            detail.setProductName(snapshot.productName());
            detail.setProductPrice(snapshot.productPrice());
            detail.setProductQuantity(snapshot.quantity());
            orderDetailMapper.insert(detail);
        }

        // 7. 清空购物车中已结算的选中商品（限定 user_id，避免越权删除他人购物车）
        List<Long> cartItemIds = selectedItems.stream().map(ShoppingCart::getId).collect(Collectors.toList());
        cartMapper.delete(new LambdaQueryWrapper<ShoppingCart>()
                .eq(ShoppingCart::getUserId, userId)
                .in(ShoppingCart::getId, cartItemIds));

        log.info("订单创建成功：orderNo={}, userId={}, totalAmount={}", orderNo, userId, totalAmount);
        return toOrderVO(orderMaster, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO pay(Long userId, Long orderId) {
        OrderMaster order = getOwnedOrder(userId, orderId);
        checkStatus(order, OrderStatusEnum.WAIT_PAY);
        int rows = orderMasterMapper.update(null, new LambdaUpdateWrapper<OrderMaster>()
                .eq(OrderMaster::getId, orderId)
                .eq(OrderMaster::getOrderStatus, OrderStatusEnum.WAIT_PAY.getCode())
                .set(OrderMaster::getOrderStatus, OrderStatusEnum.PAID.getCode())
                .set(OrderMaster::getPayTime, LocalDateTime.now()));
        if (rows == 0) {
            throw new BusinessException(ResultCode.ORDER_CONCURRENT_MODIFY);
        }
        order.setOrderStatus(OrderStatusEnum.PAID.getCode());
        order.setPayTime(LocalDateTime.now());
        return toOrderVO(order, null);
    }

    /**
     * 取消订单：仅待支付可取消（否则抛 6002）；同事务内回补库存，回补失败整体回滚
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO cancel(Long userId, Long orderId) {
        OrderMaster order = getOwnedOrder(userId, orderId);
        checkStatus(order, OrderStatusEnum.WAIT_PAY);
        cancelInternal(order);
        return toOrderVO(order, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO ship(Long orderId) {
        OrderMaster order = getById(orderId);
        checkStatus(order, OrderStatusEnum.PAID);
        int rows = orderMasterMapper.update(null, new LambdaUpdateWrapper<OrderMaster>()
                .eq(OrderMaster::getId, orderId)
                .eq(OrderMaster::getOrderStatus, OrderStatusEnum.PAID.getCode())
                .set(OrderMaster::getOrderStatus, OrderStatusEnum.SHIPPED.getCode()));
        if (rows == 0) {
            throw new BusinessException(ResultCode.ORDER_CONCURRENT_MODIFY);
        }
        order.setOrderStatus(OrderStatusEnum.SHIPPED.getCode());
        return toOrderVO(order, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO complete(Long orderId) {
        OrderMaster order = getById(orderId);
        checkStatus(order, OrderStatusEnum.SHIPPED);
        int rows = orderMasterMapper.update(null, new LambdaUpdateWrapper<OrderMaster>()
                .eq(OrderMaster::getId, orderId)
                .eq(OrderMaster::getOrderStatus, OrderStatusEnum.SHIPPED.getCode())
                .set(OrderMaster::getOrderStatus, OrderStatusEnum.FINISHED.getCode()));
        if (rows == 0) {
            throw new BusinessException(ResultCode.ORDER_CONCURRENT_MODIFY);
        }
        order.setOrderStatus(OrderStatusEnum.FINISHED.getCode());
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

        // 批量查询明细，避免每条订单再查一次明细（N+1）
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
     * 取消订单：先以条件更新抢占「唯一赢家」，再回补库存。
     * 条件更新返回 0 行说明已被其他请求处理，直接抛冲突，避免重复回补。
     */
    private void cancelInternal(OrderMaster order) {
        int rows = orderMasterMapper.update(null, new LambdaUpdateWrapper<OrderMaster>()
                .eq(OrderMaster::getId, order.getId())
                .eq(OrderMaster::getOrderStatus, OrderStatusEnum.WAIT_PAY.getCode())
                .set(OrderMaster::getOrderStatus, OrderStatusEnum.CANCELED.getCode()));
        if (rows == 0) {
            throw new BusinessException(ResultCode.ORDER_CONCURRENT_MODIFY);
        }
        order.setOrderStatus(OrderStatusEnum.CANCELED.getCode());

        List<OrderDetail> details = orderDetailMapper.selectList(new LambdaQueryWrapper<OrderDetail>()
                .eq(OrderDetail::getOrderNo, order.getOrderNo()));
        for (OrderDetail detail : details) {
            // 回补失败会抛异常，连同上面的状态变更一起回滚，订单保持待支付可被重试
            productInfoMapper.restoreStock(detail.getProductId(), detail.getProductQuantity());
            // 同上：回补也走自定义 SQL，需显式清详情缓存
            productService.evictDetailCache(detail.getProductId());
        }
        log.info("订单已取消，库存已回补：orderNo={}, 明细 {} 条", order.getOrderNo(), details.size());
    }

    /**
     * 商品校验 + 扣减库存。
     *
     * <p>正确性来自两层：
     * <ol>
     *   <li>{@code SELECT ... FOR UPDATE} 排他行锁（当前读，绕过 REPEATABLE READ 快照）
     *       ——同一商品的「判断库存 → 扣减」被串行化，这是主要保障；</li>
     *   <li>UPDATE 语句再带 {@code stock >= ? AND version = ?} 条件——数据库层兜底。</li>
     * </ol>
     * 行锁生效时条件更新不会再因版本冲突失败，因此不再保留「乐观锁重试循环」
     * （历史实现的重试分支在行锁保护下永远不会被执行，属死代码且误导）。
     */
    private DeductOutcome deductStock(ShoppingCart item) {
        ProductInfo product = productInfoMapper.selectByIdForUpdate(item.getProductId());
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        if (!ProductStatusEnum.isOnShelf(product.getStatus())) {
            throw new BusinessException(ResultCode.PRODUCT_OFF_SHELF,
                    "商品【" + product.getName() + "】已下架");
        }
        if (product.getStock() < item.getQuantity()) {
            throw new BusinessException(ResultCode.STOCK_NOT_ENOUGH,
                    "商品【" + product.getName() + "】库存不足");
        }
        int rows = productInfoMapper.deductStock(product.getId(), item.getQuantity(), product.getVersion());
        if (rows == 0) {
            // 正常路径不会走到：说明行锁未按预期生效，必须显式失败而不是当作成功
            log.error("库存条件更新未生效（疑似行锁失效）：productId={}, version={}",
                    product.getId(), product.getVersion());
            throw new BusinessException(ResultCode.STOCK_CONFLICT);
        }
        // 库存走自定义 SQL，绕过了商品服务的更新链路，必须显式清详情缓存，
        // 否则商品详情会带着旧库存存在一个过期周期（微服务版已如此处理，此前单体漏了）
        productService.evictDetailCache(product.getId());
        return new DeductOutcome(product);
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

    /** 扣减成功后的商品快照 */
    private record OrderItemSnapshot(Long productId, String productName,
                                     BigDecimal productPrice, Integer quantity) {
    }

    /** 扣减结果（携带扣减时的商品对象，供后续生成快照复用） */
    private record DeductOutcome(ProductInfo product) {
    }
}
