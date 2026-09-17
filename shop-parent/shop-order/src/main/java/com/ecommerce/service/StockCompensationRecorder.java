package com.ecommerce.service;

import com.ecommerce.common.entity.StockCompensation;
import com.ecommerce.mapper.StockCompensationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存补偿登记器（<b>独立 Bean，勿合并回 StockCompensationService</b>）。
 *
 * <p>为什么必须独立：补偿登记发生在「外层事务即将回滚」的异常分支里，必须用
 * {@link Propagation#REQUIRES_NEW} 独立提交，否则登记记录会随外层事务一起回滚，
 * 库存就永久少卖且无任何记录可重试。
 *
 * <p>而 Spring 的事务注解依赖代理生效，<b>同类内部调用（self-invocation）会绕过代理，
 * 注解形同虚设</b>。历史实现虽然在类注释里写明了这一点，却仍在
 * {@code restoreOrRecord()} 里用 {@code this.record(...)} 自调用，导致 REQUIRES_NEW
 * 从未生效——把记录方法单独拆成这个 Bean 才是真正的修复。
 */
@Slf4j
@Service
public class StockCompensationRecorder {

    /** 单条错误信息最大长度，与 last_error 列宽度保持一致 */
    private static final int MAX_ERROR_LENGTH = 240;

    private final StockCompensationMapper compensationMapper;

    public StockCompensationRecorder(StockCompensationMapper compensationMapper) {
        this.compensationMapper = compensationMapper;
    }

    /**
     * 登记补偿任务。唯一键 (order_no, product_id, biz_type) 保证同一笔回补只登记一次，
     * 重复登记直接忽略（幂等）。
     *
     * <p>REQUIRES_NEW：挂起外层事务，用独立事务提交，不受外层回滚影响。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void record(String orderNo, Long productId, Integer quantity, String bizType, String error) {
        StockCompensation entity = new StockCompensation();
        entity.setOrderNo(orderNo);
        entity.setProductId(productId);
        entity.setQuantity(quantity);
        entity.setBizType(bizType);
        entity.setStatus(StockCompensation.STATUS_PENDING);
        entity.setRetryCount(0);
        entity.setLastError(truncate(error));
        try {
            compensationMapper.insert(entity);
            log.warn("已登记库存补偿任务：orderNo={}, productId={}, bizType={}, error={}",
                    orderNo, productId, bizType, error);
        } catch (DuplicateKeyException e) {
            log.info("库存补偿任务已存在，跳过重复登记：orderNo={}, productId={}, bizType={}",
                    orderNo, productId, bizType);
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) : text;
    }
}
