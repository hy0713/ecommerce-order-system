package com.ecommerce.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ecommerce.common.dto.CartAddDTO;
import com.ecommerce.common.entity.ShoppingCart;
import com.ecommerce.common.enums.ProductStatusEnum;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.feign.ProductFeignClient;
import com.ecommerce.common.result.Result;
import com.ecommerce.common.result.ResultCode;
import com.ecommerce.common.vo.CartItemVO;
import com.ecommerce.common.vo.ProductVO;
import com.ecommerce.mapper.ShoppingCartMapper;
import com.ecommerce.service.CartService;
import lombok.extern.slf4j.Slf4j;
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
 * 商品信息通过 Feign 调用商品服务获取，不再本地查库
 */
@Slf4j
@Service
public class CartServiceImpl implements CartService {

    /** 单个购物车条目的数量上限（防止恶意传入超大数量） */
    private static final int MAX_CART_QUANTITY = 999;

    private final ShoppingCartMapper cartMapper;
    private final ProductFeignClient productFeignClient;

    public CartServiceImpl(ShoppingCartMapper cartMapper, ProductFeignClient productFeignClient) {
        this.cartMapper = cartMapper;
        this.productFeignClient = productFeignClient;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(Long userId, CartAddDTO cartAddDTO) {
        // Feign 查询商品，校验状态与库存（商品数据在商品服务）
        Result<ProductVO> productResult = productFeignClient.getById(cartAddDTO.getProductId());
        if (!productResult.isSuccess()) {
            throw new BusinessException(productResult.getCode(), productResult.getMessage());
        }
        ProductVO product = productResult.getData();
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
        if (product.getStock() < newQuantity) {
            throw new BusinessException(ResultCode.STOCK_NOT_ENOUGH,
                    "商品【" + product.getName() + "】库存不足");
        }
        if (cart != null) {
            // 同一用户同一商品数量累加，不重复生成记录
            cart.setQuantity(Math.min(newQuantity, MAX_CART_QUANTITY));
            cartMapper.updateById(cart);
        } else {
            cart = new ShoppingCart();
            cart.setUserId(userId);
            cart.setProductId(cartAddDTO.getProductId());
            cart.setQuantity(cartAddDTO.getQuantity());
            cart.setSelected(1);
            cartMapper.insert(cart);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateQuantity(Long userId, Long cartItemId, Integer quantity) {
        ShoppingCart cart = getOwned(userId, cartItemId);
        // 与 add() 保持同一校验口径：商品必须存在、在售，且数量不得超过当前库存。
        // （此前这里只做 setQuantity + updateById，购物车可被改到远超库存，与单体版行为不一致）
        Result<ProductVO> productResult = productFeignClient.getById(cart.getProductId());
        if (!productResult.isSuccess()) {
            throw new BusinessException(productResult.getCode(), productResult.getMessage());
        }
        ProductVO product = productResult.getData();
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        if (!ProductStatusEnum.isOnShelf(product.getStatus())) {
            throw new BusinessException(ResultCode.PRODUCT_OFF_SHELF);
        }
        if (quantity == null || quantity < 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "商品数量必须大于 0");
        }
        if (product.getStock() < quantity) {
            throw new BusinessException(ResultCode.STOCK_NOT_ENOUGH,
                    "商品【" + product.getName() + "】库存不足");
        }
        cart.setQuantity(Math.min(quantity, MAX_CART_QUANTITY));
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
        // Feign 批量查询商品，实时关联最新价格与状态
        String ids = carts.stream()
                .map(c -> String.valueOf(c.getProductId()))
                .collect(Collectors.joining(","));
        Result<List<ProductVO>> productsResult = productFeignClient.listByIds(ids);
        Map<Long, ProductVO> productMap = productsResult.isSuccess() && productsResult.getData() != null
                ? productsResult.getData().stream()
                        .collect(Collectors.toMap(ProductVO::getId, Function.identity()))
                : Collections.emptyMap();
        return carts.stream().map(cart -> {
            CartItemVO vo = BeanUtil.copyProperties(cart, CartItemVO.class);
            ProductVO product = productMap.get(cart.getProductId());
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
