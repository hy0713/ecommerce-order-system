package com.ecommerce.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.entity.ProductInfo;
import org.apache.ibatis.annotations.Param;

/**
 * 商品表 Mapper
 */
public interface ProductInfoMapper extends BaseMapper<ProductInfo> {

    /**
     * 乐观锁扣减库存：库存充足且版本号匹配才扣减
     *
     * @param productId 商品ID
     * @param quantity  扣减数量
     * @param version   当前版本号
     * @return 受影响行数，0 表示扣减失败（库存不足或版本不匹配）
     */
    int deductStock(@Param("productId") Long productId,
                    @Param("quantity") Integer quantity,
                    @Param("version") Integer version);

    /**
     * 回补库存（取消订单），同时自增版本号
     */
    int restoreStock(@Param("productId") Long productId, @Param("quantity") Integer quantity);

    /**
     * 当前读（行锁）查询商品：事务内重读时绕过 REPEATABLE READ 快照，
     * 保证乐观锁重试拿到最新版本号
     */
    ProductInfo selectByIdForUpdate(@Param("id") Long id);
}
