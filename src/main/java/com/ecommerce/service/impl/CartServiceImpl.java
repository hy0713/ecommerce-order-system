package com.ecommerce.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ecommerce.common.enums.ProductStatusEnum;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ResultCode;
import com.ecommerce.dto.CartAddDTO;
import com.ecommerce.entity.ProductInfo;
import com.ecommerce.entity.ShoppingCart;
import com.ecommerce.mapper.ProductInfoMapper;
import com.ecommerce.mapper.ShoppingCartMapper;
import com.ecommerce.service.CartService;
import com.ecommerce.vo.CartItemVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 购物车服务实现
 */
@Service
public class CartServiceImpl implements CartService {

    /** 单个购物车条目的数量上限（与前端控件保持一致） */
    private static final int CART_ITEM_MAX_QUANTITY = 999;

    private final ShoppingCartMapper cartMapper;
    private final ProductInfoMapper productInfoMapper;

    public CartServiceImpl(ShoppingCartMapper cartMapper, ProductInfoMapper productInfoMapper) {
        this.cartMapper = cartMapper;
        this.productInfoMapper = productInfoMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(Long userId, CartAddDTO cartAddDTO) {
        ProductInfo product = productInfoMapper.selectById(cartAddDTO.getProductId());
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        // 已下架商品不可加入购物车
        if (!ProductStatusEnum.isOnShelf(product.getStatus())) {
            throw new BusinessException(ResultCode.PRODUCT_OFF_SHELF);
        }
        ShoppingCart cart = cartMapper.selectOne(new LambdaQueryWrapper<ShoppingCart>()
                .eq(ShoppingCart::getUserId, userId)
                .eq(ShoppingCart::getProductId, cartAddDTO.getProductId()));
        // 校验库存：加购后的累计数量不得超过当前库存
        int newQuantity = cart == null
                ? cartAddDTO.getQuantity()
                : cart.getQuantity() + cartAddDTO.getQuantity();
        // 上限截断后再校验，保证「校验值」与「写入值」一致（历史实现用未截断值校验）
        int finalQuantity = Math.min(newQuantity, CART_ITEM_MAX_QUANTITY);
        if (finalQuantity < 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "购买数量必须大于 0");
        }
        if (product.getStock() < finalQuantity) {
            throw new BusinessException(ResultCode.STOCK_NOT_ENOUGH,
                    "商品【" + product.getName() + "】库存不足");
        }
        if (cart != null) {
            // 同一用户同一商品数量累加，不重复生成记录
            cart.setQuantity(finalQuantity);
            cartMapper.updateById(cart);
        } else {
            cart = new ShoppingCart();
            cart.setUserId(userId);
            cart.setProductId(cartAddDTO.getProductId());
            cart.setQuantity(finalQuantity);
            cart.setSelected(1);
            cartMapper.insert(cart);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateQuantity(Long userId, Long cartItemId, Integer quantity) {
        if (quantity == null || quantity < 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "购买数量必须大于 0");
        }
        ShoppingCart cart = getOwned(userId, cartItemId);
        int finalQuantity = Math.min(quantity, CART_ITEM_MAX_QUANTITY);
        // 与 add 保持同一套库存校验口径，避免改数量绕过库存限制
        ProductInfo product = productInfoMapper.selectById(cart.getProductId());
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        if (!ProductStatusEnum.isOnShelf(product.getStatus())) {
            throw new BusinessException(ResultCode.PRODUCT_OFF_SHELF,
                    "商品【" + product.getName() + "】已下架");
        }
        if (product.getStock() < finalQuantity) {
            throw new BusinessException(ResultCode.STOCK_NOT_ENOUGH,
                    "商品【" + product.getName() + "】库存不足");
        }
        cart.setQuantity(finalQuantity);
        cartMapper.updateById(cart);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long cartItemId) {
        getOwned(userId, cartItemId);
        cartMapper.deleteById(cartItemId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void selectAll(Long userId, boolean selected) {
        cartMapper.update(null, new LambdaUpdateWrapper<ShoppingCart>()
                .eq(ShoppingCart::getUserId, userId)
                .set(ShoppingCart::getSelected, selected ? 1 : 0));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void selectOne(Long userId, Long cartItemId, boolean selected) {
        getOwned(userId, cartItemId);   // 归属校验：只能操作自己的条目
        cartMapper.update(null, new LambdaUpdateWrapper<ShoppingCart>()
                .eq(ShoppingCart::getId, cartItemId)
                .eq(ShoppingCart::getUserId, userId)
                .set(ShoppingCart::getSelected, selected ? 1 : 0));
    }

    @Override
    public List<CartItemVO> list(Long userId) {
        List<ShoppingCart> carts = cartMapper.selectList(new LambdaQueryWrapper<ShoppingCart>()
                .eq(ShoppingCart::getUserId, userId)
                .orderByDesc(ShoppingCart::getCreateTime));
        if (carts.isEmpty()) {
            return Collections.emptyList();
        }
        // 批量查询商品，实时关联最新价格与状态
        List<Long> productIds = carts.stream().map(ShoppingCart::getProductId).collect(Collectors.toList());
        Map<Long, ProductInfo> productMap = productInfoMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(ProductInfo::getId, Function.identity()));
        return carts.stream().map(cart -> {
            CartItemVO vo = BeanUtil.copyProperties(cart, CartItemVO.class);
            ProductInfo product = productMap.get(cart.getProductId());
            if (product != null) {
                vo.setProductName(product.getName());
                vo.setProductPrice(product.getPrice());
                vo.setProductIcon(product.getIcon());
                vo.setStock(product.getStock());
                vo.setProductStatus(product.getStatus());
                // 异常商品自动标记：已下架或库存不足
                vo.setInvalid(!ProductStatusEnum.isOnShelf(product.getStatus())
                        || product.getStock() < cart.getQuantity());
            } else {
                vo.setProductName("商品已删除");
                vo.setProductStatus(0);
                vo.setStock(0);
                vo.setInvalid(Boolean.TRUE);
            }
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public List<ShoppingCart> listSelected(Long userId) {
        return cartMapper.selectList(new LambdaQueryWrapper<ShoppingCart>()
                .eq(ShoppingCart::getUserId, userId)
                .eq(ShoppingCart::getSelected, 1));
    }

    private ShoppingCart getOwned(Long userId, Long cartItemId) {
        ShoppingCart cart = cartMapper.selectById(cartItemId);
        if (cart == null || !Objects.equals(cart.getUserId(), userId)) {
            throw new BusinessException(ResultCode.CART_ITEM_NOT_FOUND);
        }
        return cart;
    }
}
