package com.ecommerce.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.common.entity.StockCompensation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 库存补偿流水 Mapper
 */
public interface StockCompensationMapper extends BaseMapper<StockCompensation> {
    /** 必须在事务内调用；锁保持到回补结果落库提交，其他 worker 跳过正在处理的行。 */
    @Select("SELECT * FROM stock_compensation WHERE id = #{id} AND status = 0 "
            + "AND retry_count = #{retryCount} AND retry_count < 10 FOR UPDATE SKIP LOCKED")
    StockCompensation selectPendingForUpdate(@Param("id") Long id, @Param("retryCount") Integer retryCount);
}
