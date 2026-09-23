package com.ecommerce.service.impl;

import com.ecommerce.common.dto.OrderCreateDTO;
import com.ecommerce.common.entity.OrderDetail;
import com.ecommerce.common.entity.OrderMaster;
import com.ecommerce.common.entity.ShoppingCart;
import com.ecommerce.common.entity.StockCompensation;
import com.ecommerce.common.entity.UserAddress;
import com.ecommerce.common.feign.ProductFeignClient;
import com.ecommerce.common.feign.StockDeductResult;
import com.ecommerce.common.feign.UserFeignClient;
import com.ecommerce.common.result.Result;
import com.ecommerce.config.RabbitMQConfig;
import com.ecommerce.mapper.OrderDetailMapper;
import com.ecommerce.mapper.OrderMasterMapper;
import com.ecommerce.mapper.ShoppingCartMapper;
import com.ecommerce.service.CartService;
import com.ecommerce.service.StockCompensationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderCreateCompensationTest {
    private OrderMasterMapper master;
    private OrderDetailMapper detail;
    private ShoppingCartMapper cart;
    private StockCompensationService compensation;
    private RabbitTemplate rabbit;
    private OrderServiceImpl service;
    private OrderCreateDTO request;

    @BeforeEach
    void setUp() {
        master = mock(OrderMasterMapper.class);
        detail = mock(OrderDetailMapper.class);
        cart = mock(ShoppingCartMapper.class);
        CartService carts = mock(CartService.class);
        ProductFeignClient products = mock(ProductFeignClient.class);
        UserFeignClient users = mock(UserFeignClient.class);
        compensation = mock(StockCompensationService.class);
        rabbit = mock(RabbitTemplate.class);
        service = new OrderServiceImpl(master, detail, cart, carts, products, users, rabbit, compensation);
        request = new OrderCreateDTO();
        request.setAddressId(1L);
        when(users.getAddress(1L)).thenReturn(Result.success(new UserAddress()));
        when(carts.listSelected(7L)).thenReturn(List.of(item(11L, 101L, 2), item(12L, 102L, 3)));
        when(products.deductStock(any())).thenReturn(Result.success(StockDeductResult.ok("product", BigDecimal.TEN)));
        when(detail.selectList(any())).thenReturn(List.of());
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @ParameterizedTest
    @ValueSource(strings = {"master", "detail", "cart"})
    void localWriteFailureCompensatesEverySuccessfulDeductionAndRethrows(String stage) {
        RuntimeException failure = new IllegalStateException("local write failed: " + stage);
        if (stage.equals("master")) {
            when(master.insert(any(OrderMaster.class))).thenThrow(failure);
        } else if (stage.equals("detail")) {
            when(detail.insert(any(OrderDetail.class))).thenReturn(1).thenThrow(failure);
        } else {
            when(cart.delete(any())).thenThrow(failure);
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.create(7L, request)));
        ArgumentCaptor<String> orderNos = ArgumentCaptor.forClass(String.class);
        verify(compensation).restoreOrRecord(orderNos.capture(), eq(101L), eq(2), eq(StockCompensation.BIZ_CREATE_FAIL));
        verify(compensation).restoreOrRecord(orderNos.capture(), eq(102L), eq(3), eq(StockCompensation.BIZ_CREATE_FAIL));
        assertFalse(orderNos.getAllValues().get(0).isBlank());
        assertEquals(orderNos.getAllValues().get(0), orderNos.getAllValues().get(1));
        verifyNoMoreInteractions(compensation);
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        verifyNoInteractions(rabbit);
    }

    @Test
    void responseQueryFailureCompensatesAndRethrowsWithoutSendingMessage() {
        RuntimeException failure = new IllegalStateException("response query failed");
        when(detail.selectList(any())).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.create(7L, request)));
        verify(master).insert(any(OrderMaster.class));
        verify(detail, times(2)).insert(any(OrderDetail.class));
        verify(cart).delete(any());
        ArgumentCaptor<String> orderNos = ArgumentCaptor.forClass(String.class);
        verify(compensation).restoreOrRecord(orderNos.capture(), eq(101L), eq(2), eq(StockCompensation.BIZ_CREATE_FAIL));
        verify(compensation).restoreOrRecord(orderNos.capture(), eq(102L), eq(3), eq(StockCompensation.BIZ_CREATE_FAIL));
        assertFalse(orderNos.getAllValues().get(0).isBlank());
        assertEquals(orderNos.getAllValues().get(0), orderNos.getAllValues().get(1));
        verifyNoMoreInteractions(compensation);
        List<TransactionSynchronization> callbacks = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, callbacks.size());
        callbacks.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verifyNoInteractions(rabbit);
    }

    @Test
    void successfulCreateDoesNotCompensateAndDefersMessageUntilCommit() {
        var order = service.create(7L, request);
        verify(master).insert(any(OrderMaster.class));
        verify(detail, times(2)).insert(any(OrderDetail.class));
        verify(cart).delete(any());
        verifyNoInteractions(compensation, rabbit);
        List<TransactionSynchronization> callbacks = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, callbacks.size());
        callbacks.get(0).afterCommit();
        verify(rabbit).convertAndSend(RabbitMQConfig.ORDER_EXCHANGE, RabbitMQConfig.WAIT_ROUTING_KEY, order.getOrderNo());
    }

    private ShoppingCart item(long id, long productId, int quantity) {
        ShoppingCart item = new ShoppingCart();
        item.setId(id);
        item.setProductId(productId);
        item.setQuantity(quantity);
        return item;
    }
}
