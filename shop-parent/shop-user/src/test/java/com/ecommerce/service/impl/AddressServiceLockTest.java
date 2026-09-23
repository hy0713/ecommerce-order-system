package com.ecommerce.service.impl;

import com.ecommerce.common.dto.AddressDTO;
import com.ecommerce.common.entity.UserAddress;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.mapper.UserAddressMapper;
import com.ecommerce.mapper.UserMapper;
import com.ecommerce.service.AddressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AddressServiceLockTest {
    private UserAddressMapper addresses;
    private UserMapper users;
    private PlatformTransactionManager transactions;
    private AddressService service;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "test"),
                UserAddress.class);
        addresses = mock(UserAddressMapper.class);
        users = mock(UserMapper.class);
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(users.lockById(anyLong())).thenAnswer(call -> call.getArgument(0));
        ProxyFactory proxy = new ProxyFactory(new AddressServiceImpl(addresses, users));
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (AddressService) proxy.getProxy();
    }

    @ParameterizedTest
    @ValueSource(strings = {"add", "update", "delete", "setDefault"})
    void locksCurrentUserInsideTransactionBeforeAnyAddressAccess(String operation) {
        for (long userId : new long[]{7L, 8L}) {
            UserAddress address = address(userId);
            when(addresses.selectCount(any())).thenReturn(0L);
            when(addresses.selectById(11L)).thenReturn(address);
            UserAddress remaining = address(userId);
            remaining.setId(12L);
            remaining.setIsDefault(0);
            when(addresses.selectOne(any())).thenReturn(remaining);
            AddressDTO dto = new AddressDTO();
            dto.setIsDefault(1);
            if (operation.equals("add")) service.add(userId, dto);
            else if (operation.equals("update")) service.update(userId, 11L, dto);
            else if (operation.equals("delete")) service.delete(userId, 11L);
            else service.setDefault(userId, 11L);

            var order = inOrder(transactions, users, addresses);
            order.verify(transactions).getTransaction(any());
            order.verify(users).lockById(userId);
            if (operation.equals("add")) {
                order.verify(addresses).selectCount(any());
                order.verify(addresses).update(isNull(), any());
                order.verify(addresses).insert(argThat(a -> a.getIsDefault() == 1 && a.getUserId().equals(userId)));
            } else {
                order.verify(addresses).selectById(11L);
                if (operation.equals("update")) order.verify(addresses).updateById(address);
                else if (operation.equals("delete")) {
                    order.verify(addresses).deleteById(11L);
                    order.verify(addresses).selectOne(any());
                    order.verify(addresses).updateById(remaining);
                    assertEquals(1, remaining.getIsDefault());
                } else order.verify(addresses, times(2)).update(isNull(), any());
            }
            order.verify(transactions).commit(any());
            verifyNoMoreInteractions(users, addresses, transactions);
            clearInvocations(users, addresses, transactions);
        }
    }

    @Test
    void ownershipFailureRollsBackAfterLockWithoutWritingAddress() {
        when(addresses.selectById(11L)).thenReturn(address(8L));
        assertThrows(BusinessException.class, () -> service.setDefault(7L, 11L));
        var order = inOrder(transactions, users, addresses);
        order.verify(transactions).getTransaction(any());
        order.verify(users).lockById(7L);
        order.verify(addresses).selectById(11L);
        order.verify(transactions).rollback(any());
        verifyNoMoreInteractions(users, addresses, transactions);
    }

    @Test
    void missingUserCannotModifyAddressesWithoutLock() {
        when(users.lockById(7L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.add(7L, new AddressDTO()));
        verifyNoInteractions(addresses);
        verify(transactions).rollback(any());
    }

    private UserAddress address(long userId) {
        UserAddress address = new UserAddress();
        address.setId(11L);
        address.setUserId(userId);
        address.setIsDefault(1);
        return address;
    }
}
