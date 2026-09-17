package com.ecommerce.service;

import com.ecommerce.common.dto.CartAddDTO;
import com.ecommerce.common.entity.ShoppingCart;
import com.ecommerce.common.vo.CartItemVO;

import java.util.List;

/**
 * 购物车服务
 */
public interface CartService {

    /**
     * 添加商品到购物车（同用户同商品数量累加）
     */
    void add(Long userId, CartAddDTO cartAddDTO);

    /**
     * 修改购物车商品数量（校验商品存在、上架、库存充足）
     */
    void updateQuantity(Long userId, Long cartItemId, Integer quantity);

    /**
     * 删除购物车条目
     */
    void delete(Long userId, Long cartItemId);

    /**
     * 全选 / 取消全选
     */
    void selectAll(Long userId, boolean selected);

    /**
     * 勾选 / 取消勾选单个购物车条目。
     * <p>此前 C 端「单选」是拿全选接口模拟的，逻辑上不可能正确（选一个会变成全选或被全部取消），
     * 因此补这个精确到条目的接口。
     */
    void selectOne(Long userId, Long cartItemId, boolean selected);

    /**
     * 购物车列表（Feign 实时关联商品价格与状态）
     */
    List<CartItemVO> list(Long userId);

    /**
     * 查询用户选中状态的购物车条目
     */
    List<ShoppingCart> listSelected(Long userId);
}
