package com.ecommerce.service;

import com.ecommerce.common.entity.StockCompensation;
import com.ecommerce.common.feign.ProductFeignClient;
import com.ecommerce.common.result.Result;
import com.ecommerce.mapper.StockCompensationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StockCompensationRetryTest {
    private StockCompensationMapper mapper;
    private ProductFeignClient products;
    private PlatformTransactionManager transactions;
    private StockCompensationService first;
    private StockCompensationService second;
    private final ReentrantLock rowLock = new ReentrantLock();
    private final ThreadLocal<Boolean> active = new ThreadLocal<>();
    private final ThreadLocal<StockCompensation> staged = new ThreadLocal<>();
    private final AtomicReference<StockCompensation> stored = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "test"),
                StockCompensation.class);
        mapper = mock(StockCompensationMapper.class);
        products = mock(ProductFeignClient.class);
        transactions = mock(PlatformTransactionManager.class);
        StockCompensation task = new StockCompensation();
        task.setId(1L);
        task.setOrderNo("order-1");
        task.setProductId(101L);
        task.setQuantity(2);
        task.setStatus(StockCompensation.STATUS_PENDING);
        task.setRetryCount(0);
        stored.set(task);
        // Return stale candidates even after completion: the locked read must recheck them.
        when(mapper.selectList(any())).thenAnswer(invocation -> List.of(copy(stored.get())));
        when(transactions.getTransaction(any())).thenAnswer(invocation -> {
            TransactionDefinition definition = invocation.getArgument(0);
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getPropagationBehavior());
            assertNull(active.get());
            active.set(true);
            return new SimpleTransactionStatus();
        });
        // Model a database row lock tied to transaction lifetime, shared by two service instances.
        when(mapper.selectPendingForUpdate(anyLong(), anyInt())).thenAnswer(invocation -> {
            assertEquals(Boolean.TRUE, active.get());
            if (!rowLock.tryLock()) {
                return null;
            }
            StockCompensation current = stored.get();
            if (current.getStatus() != StockCompensation.STATUS_PENDING || current.getRetryCount() >= 10
                    || !current.getRetryCount().equals(invocation.getArgument(1))) {
                return null;
            }
            staged.set(copy(current));
            return copy(current);
        });
        when(mapper.update(isNull(), any())).thenAnswer(invocation -> {
            assertTrue(rowLock.isHeldByCurrentThread());
            assertEquals(Boolean.TRUE, active.get());
            LambdaUpdateWrapper<StockCompensation> update = invocation.getArgument(1);
            if (update.getSqlSet().contains("retry_count")) {
                staged.get().setRetryCount(staged.get().getRetryCount() + 1);
            } else {
                staged.get().setStatus(StockCompensation.STATUS_DONE);
            }
            return 1;
        });
        doAnswer(invocation -> {
            if (staged.get() != null) {
                stored.set(staged.get());
            }
            releaseTransaction();
            return null;
        }).when(transactions).commit(any());
        doAnswer(invocation -> {
            releaseTransaction();
            return null;
        }).when(transactions).rollback(any());
        first = new StockCompensationService(mapper, products, mock(StockCompensationRecorder.class), transactions);
        second = new StockCompensationService(mapper, products, mock(StockCompensationRecorder.class), transactions);
    }

    @Test
    void overlappingWorkersCallRestoreOnlyOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(products.restoreStock(any())).thenAnswer(invocation -> {
            assertTrue(rowLock.isHeldByCurrentThread());
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return Result.success();
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> first.retryPending(1));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var two = executor.submit(() -> second.retryPending(1));
            assertEquals(0, two.get(5, TimeUnit.SECONDS));
            release.countDown();
            assertEquals(1, one.get(5, TimeUnit.SECONDS));
            assertEquals(0, second.retryPending(1));
            verify(products, times(1)).restoreStock(any());
            assertEquals(StockCompensation.STATUS_DONE, stored.get().getStatus());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void remoteFailureRemainsPendingAndCanRetry() {
        when(products.restoreStock(any())).thenThrow(new IllegalStateException("remote failure"))
                .thenReturn(Result.success());
        assertEquals(0, first.retryPending(1));
        assertEquals(StockCompensation.STATUS_PENDING, stored.get().getStatus());
        assertEquals(1, stored.get().getRetryCount());
        assertFalse(rowLock.isLocked());
        assertEquals(1, second.retryPending(1));
        verify(products, times(2)).restoreStock(any());
        assertEquals(StockCompensation.STATUS_DONE, stored.get().getStatus());
    }

    @Test
    void retryLimitStopsFurtherRemoteCalls() {
        stored.get().setRetryCount(9);
        when(products.restoreStock(any())).thenReturn(Result.error(500, "unavailable"));
        assertEquals(0, first.retryPending(1));
        assertEquals(10, stored.get().getRetryCount());
        assertEquals(StockCompensation.STATUS_PENDING, stored.get().getStatus());
        assertEquals(0, second.retryPending(1));
        verify(products, times(1)).restoreStock(any());
    }

    @Test
    void statusWriteFailureRollsBackAndReleasesLock() {
        when(products.restoreStock(any())).thenReturn(Result.success());
        doThrow(new IllegalStateException("database failure")).when(mapper).update(isNull(), any());
        assertEquals(0, first.retryPending(1));
        verify(transactions).rollback(any());
        assertFalse(rowLock.isLocked());
        assertEquals(StockCompensation.STATUS_PENDING, stored.get().getStatus());
        assertEquals(0, stored.get().getRetryCount());
    }

    private void releaseTransaction() {
        staged.remove();
        active.remove();
        if (rowLock.isHeldByCurrentThread()) {
            rowLock.unlock();
        }
    }

    private StockCompensation copy(StockCompensation source) {
        StockCompensation result = new StockCompensation();
        result.setId(source.getId());
        result.setOrderNo(source.getOrderNo());
        result.setProductId(source.getProductId());
        result.setQuantity(source.getQuantity());
        result.setStatus(source.getStatus());
        result.setRetryCount(source.getRetryCount());
        return result;
    }
}
