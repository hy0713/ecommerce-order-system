package com.ecommerce.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecommerce.common.constant.RedisKeyConstant;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ResultCode;
import com.ecommerce.common.util.RedisUtil;
import com.ecommerce.dto.ProductDTO;
import com.ecommerce.dto.ProductQueryDTO;
import com.ecommerce.entity.ProductCategory;
import com.ecommerce.entity.ProductInfo;
import com.ecommerce.mapper.ProductCategoryMapper;
import com.ecommerce.mapper.ProductInfoMapper;
import com.ecommerce.service.ProductService;
import com.ecommerce.vo.ProductVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商品服务实现
 */
@Slf4j
@Service
public class ProductServiceImpl implements ProductService {

    /** 缓存空值标记，防缓存穿透 */
    private static final String CACHE_EMPTY = "EMPTY";

    private final ProductInfoMapper productInfoMapper;
    private final ProductCategoryMapper categoryMapper;
    private final RedisUtil redisUtil;

    public ProductServiceImpl(ProductInfoMapper productInfoMapper,
                              ProductCategoryMapper categoryMapper,
                              RedisUtil redisUtil) {
        this.productInfoMapper = productInfoMapper;
        this.categoryMapper = categoryMapper;
        this.redisUtil = redisUtil;
    }

    @Override
    public Page<ProductVO> page(ProductQueryDTO queryDTO) {
        // 分类过滤含子分类：商品只挂在叶子分类（二级）上，按一级分类 id 精确匹配会恒为 0 条
        List<Long> categoryIds = queryDTO.getCategoryId() == null
                ? null
                : resolveCategoryIds(queryDTO.getCategoryId());

        LambdaQueryWrapper<ProductInfo> wrapper = new LambdaQueryWrapper<ProductInfo>()
                .like(StrUtil.isNotBlank(queryDTO.getKeyword()), ProductInfo::getName, queryDTO.getKeyword())
                .in(queryDTO.getCategoryId() != null, ProductInfo::getCategoryId, categoryIds)
                .ge(queryDTO.getMinPrice() != null, ProductInfo::getPrice, queryDTO.getMinPrice())
                .le(queryDTO.getMaxPrice() != null, ProductInfo::getPrice, queryDTO.getMaxPrice())
                .eq(queryDTO.getStatus() != null, ProductInfo::getStatus, queryDTO.getStatus())
                .orderByDesc(ProductInfo::getCreateTime);

        Page<ProductInfo> page = productInfoMapper.selectPage(
                new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);

        Map<Long, String> categoryNameMap = categoryNameMap();
        Page<ProductVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        List<ProductVO> records = page.getRecords().stream()
                .map(p -> toProductVO(p, categoryNameMap))
                .collect(Collectors.toList());
        voPage.setRecords(records);
        return voPage;
    }

    @Override
    public ProductVO detail(Long id) {
        String cacheKey = RedisKeyConstant.PRODUCT_DETAIL_PREFIX + id;
        // 1. 查缓存
        String cached = redisUtil.get(cacheKey);
        if (cached != null) {
            if (CACHE_EMPTY.equals(cached)) {
                return null;
            }
            ProductVO vo = redisUtil.getObject(cacheKey, ProductVO.class);
            if (vo != null) {
                return vo;
            }
        }
        // 2. 缓存未命中，查数据库
        ProductInfo product = productInfoMapper.selectById(id);
        if (product == null) {
            // 3. 空值缓存，防止缓存穿透
            redisUtil.set(cacheKey, CACHE_EMPTY, RedisKeyConstant.EMPTY_CACHE_EXPIRE_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS);
            return null;
        }
        ProductVO vo = toProductVO(product, categoryNameMap());
        redisUtil.setObject(cacheKey, vo, RedisKeyConstant.PRODUCT_DETAIL_EXPIRE_SECONDS);
        log.debug("商品详情缓存写入：productId={}", id);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long add(ProductDTO productDTO) {
        ensureCategoryExists(productDTO.getCategoryId());
        ProductInfo product = new ProductInfo();
        BeanUtil.copyProperties(productDTO, product);
        product.setVersion(0);
        if (product.getStatus() == null) {
            product.setStatus(1);
        }
        productInfoMapper.insert(product);
        return product.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ProductDTO productDTO) {
        ProductInfo product = getById(id);
        ensureCategoryExists(productDTO.getCategoryId());
        product.setCategoryId(productDTO.getCategoryId());
        product.setName(productDTO.getName());
        product.setPrice(productDTO.getPrice());
        product.setStock(productDTO.getStock());
        product.setDescription(productDTO.getDescription());
        product.setIcon(productDTO.getIcon());
        if (productDTO.getStatus() != null) {
            product.setStatus(productDTO.getStatus());
        }
        updateByIdOrThrow(product, "修改商品");
        clearCache(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "商品状态参数错误");
        }
        ProductInfo product = getById(id);
        product.setStatus(status);
        updateByIdOrThrow(product, "商品上下架");
        // 上下架同步更新缓存，保证数据一致性
        clearCache(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        getById(id);
        productInfoMapper.deleteById(id);
        clearCache(id);
    }

    private ProductInfo getById(Long id) {
        ProductInfo product = productInfoMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        return product;
    }

    /**
     * updateById 并校验受影响行数。
     *
     * <p>ProductInfo 上有 {@code @Version}，注册 OptimisticLockerInnerInterceptor 后
     * 版本冲突时 MP 返回 0 行而不抛异常；若不校验就会出现「接口返回成功、数据库没变、
     * 缓存却已清除」的假成功。
     */
    private void updateByIdOrThrow(ProductInfo product, String action) {
        int rows = productInfoMapper.updateById(product);
        if (rows == 0) {
            log.warn("{}未生效（并发修改或商品已删除）：productId={}, version={}",
                    action, product.getId(), product.getVersion());
            throw new BusinessException(ResultCode.DATA_CONFLICT, "操作未生效，数据已被其他请求修改，请刷新后重试");
        }
    }

    private void ensureCategoryExists(Long categoryId) {
        if (categoryMapper.selectById(categoryId) == null) {
            throw new BusinessException(ResultCode.CATEGORY_NOT_FOUND);
        }
    }

    /**
     * 分类查询范围 = 分类自身 + 其直接子分类。
     * 分类树只有两级（一级 1-4 → 二级 11/12/21...），商品全部挂在二级分类上，
     * 因此按一级分类 id 精确匹配必然 0 条。前端点分类入口时传的就是一级分类 id。
     */
    private List<Long> resolveCategoryIds(Long categoryId) {
        List<Long> ids = new ArrayList<>();
        ids.add(categoryId);
        List<ProductCategory> children = categoryMapper.selectList(new LambdaQueryWrapper<ProductCategory>()
                .eq(ProductCategory::getParentId, categoryId));
        children.forEach(c -> ids.add(c.getId()));
        return ids;
    }

    private void clearCache(Long id) {
        redisUtil.delete(RedisKeyConstant.PRODUCT_DETAIL_PREFIX + id);
    }

    @Override
    public void evictDetailCache(Long productId) {
        if (productId != null) {
            clearCache(productId);
        }
    }

    private ProductVO toProductVO(ProductInfo product, Map<Long, String> categoryNameMap) {
        ProductVO vo = BeanUtil.copyProperties(product, ProductVO.class);
        vo.setCategoryName(categoryNameMap.get(product.getCategoryId()));
        return vo;
    }

    private Map<Long, String> categoryNameMap() {
        List<ProductCategory> categories = categoryMapper.selectList(new LambdaQueryWrapper<>());
        return categories.stream().collect(Collectors.toMap(ProductCategory::getId, ProductCategory::getName,
                (a, b) -> a));
    }
}
