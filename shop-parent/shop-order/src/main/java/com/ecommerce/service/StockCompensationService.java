package com.ecommerce.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ecommerce.common.entity.StockCompensation;
import com.ecommerce.common.feign.ProductFeignClient;
import com.ecommerce.common.feign.StockRestoreDTO;
import com.ecommerce.common.result.Result;
import com.ecommerce.mapper.StockCompensationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 库存补偿服务：把「应当回补但失败」的库存回补落库并重试，保证跨服务最终一致。
 *
 * <p><b>注意</b>：真正的登记动作在独立 Bean {@link StockCompensationRecorder} 里。
 * 补偿登记是在「外层事务即将回滚」的异常分支中发生的，必须用 REQUIRES_NEW 独立提交；
 * 而 Spring 的事务注解靠代理生效，同类内部调用会绕过代理导致注解失效（历史 bug），
 * 所以绝不要把 record 逻辑挪回本类用 this 调用。
 */
@Slf4j
@Service
public class StockCompensationService {

    /** 单条补偿任务最大重试次数，超过后保留待人工介入 */
    private static final int MAX_RETRY = 10;

    private final StockCompensationMapper compensationMapper;
    private final ProductFeignClient productFeignClient;
    private final StockCompensationRecorder compensationRecorder;

    public StockCompensationService(StockCompensationMapper compensationMapper,
                                    ProductFeignClient productFeignClient,
                                    StockCompensationRecorder compensationRecorder) {
        this.compensationMapper = compensationMapper;
        this.productFeignClient = productFeignClient;
        this.compensationRecorder = compensationRecorder;
    }

    /**
     * 尝试回补库存；失败则登记补偿任务（独立事务提交，不受外层回滚影响）。
     *
     * @param orderNo   关联订单号
     * @param productId 商品ID
     * @param quantity  数量
     * @param bizType   {@link StockCompensation#BIZ_ORDER_CANCEL} / {@link StockCompensation#BIZ_CREATE_FAIL}
     */
    public void restoreOrRecord(String orderNo, Long productId, Integer quantity, String bizType) {
        if (productId == null || quantity == null || quantity <= 0) {
            log.warn("跳过非法库存回补请求：orderNo={}, productId={}, quantity={}", orderNo, productId, quantity);
            return;
        }
        try {
            StockRestoreDTO dto = new StockRestoreDTO();
            dto.setProductId(productId);
            dto.setQuantity(quantity);
            Result<Void> result = productFeignClient.restoreStock(dto);
            if (result != null && result.isSuccess()) {
                return;
            }
            String message = result == null ? "服务无响应" : result.getMessage();
            compensationRecorder.record(orderNo, productId, quantity, bizType, message);
        } catch (Exception e) {
            log.error("库存回补调用失败，转补偿任务：orderNo={}, productId={}", orderNo, productId, e);
            compensationRecorder.record(orderNo, productId, quantity, bizType,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 重试一批待处理补偿任务。逐条独立提交，单条失败不影响其他任务。
     *
     * @return 成功条数
     */
    public int retryPending(int batchSize) {
        List<StockCompensation> pending = compensationMapper.selectList(
                new LambdaQueryWrapper<StockCompensation>()
                        .eq(StockCompensation::getStatus, StockCompensation.STATUS_PENDING)
                        .lt(StockCompensation::getRetryCount, MAX_RETRY)
                        .orderByAsc(StockCompensation::getCreateTime)
                        .last("LIMIT " + Math.max(1, batchSize)));
        if (pending.isEmpty()) {
            return 0;
        }
        int success = 0;
        for (StockCompensation task : pending) {
            try {
                StockRestoreDTO dto = new StockRestoreDTO();
                dto.setProductId(task.getProductId());
                dto.setQuantity(task.getQuantity());
                Result<Void> result = productFeignClient.restoreStock(dto);
                if (result != null && result.isSuccess()) {
                    markDone(task);
                    success++;
                } else {
                    markRetry(task, result == null ? "服务无响应" : result.getMessage());
                }
            } catch (Exception e) {
                markRetry(task, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        log.info("库存补偿重试完成：本轮 {} 条，成功 {} 条", pending.size(), success);
        return success;
    }

    private void markDone(StockCompensation task) {
        int rows = compensationMapper.update(null, new LambdaUpdateWrapper<StockCompensation>()
                .eq(StockCompensation::getId, task.getId())
                .eq(StockCompensation::getStatus, StockCompensation.STATUS_PENDING)
                .set(StockCompensation::getStatus, StockCompensation.STATUS_DONE)
                .set(StockCompensation::getLastError, null));
        if (rows > 0) {
            log.info("库存补偿重试成功：orderNo={}, productId={}", task.getOrderNo(), task.getProductId());
        }
    }

    private void markRetry(StockCompensation task, String error) {
        compensationMapper.update(null, new LambdaUpdateWrapper<StockCompensation>()
                .eq(StockCompensation::getId, task.getId())
                .eq(StockCompensation::getStatus, StockCompensation.STATUS_PENDING)
                .set(StockCompensation::getRetryCount, task.getRetryCount() + 1)
                .set(StockCompensation::getLastError, truncate(error)));
        log.warn("库存补偿重试失败：orderNo={}, productId={}, retryCount={}, error={}",
                task.getOrderNo(), task.getProductId(), task.getRetryCount() + 1, error);
    }

    /** 当前待处理补偿任务数（用于健康检查/运维观测） */
    public long pendingCount() {
        Long count = compensationMapper.selectCount(new LambdaQueryWrapper<StockCompensation>()
                .eq(StockCompensation::getStatus, StockCompensation.STATUS_PENDING));
        return count == null ? 0L : count;
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 240 ? text.substring(0, 240) : text;
    }
}
