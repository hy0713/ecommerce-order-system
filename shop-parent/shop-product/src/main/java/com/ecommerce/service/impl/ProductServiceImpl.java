package com.ecommerce.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecommerce.common.constant.RedisKeyConstant;
import com.ecommerce.common.dto.ProductDTO;
import com.ecommerce.common.dto.ProductQueryDTO;
import com.ecommerce.common.entity.ProductCategory;
import com.ecommerce.common.entity.ProductInfo;
import com.ecommerce.common.enums.ProductStatusEnum;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.feign.StockDeductDTO;
import com.ecommerce.common.feign.StockDeductResult;
import com.ecommerce.common.result.ResultCode;
import com.ecommerce.common.util.RedisUtil;
import com.ecommerce.mapper.ProductCategoryMapper;
import com.ecommerce.mapper.ProductInfoMapper;
import com.ecommerce.service.ProductService;
import com.ecommerce.common.vo.ProductVO;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
    private final RedissonClient redissonClient;

    @Value("${redisson.lock-wait-seconds}")
    private long lockWaitSeconds;

    @Value("${redisson.lock-lease-seconds}")
    private long lockLeaseSeconds;

    public ProductServiceImpl(ProductInfoMapper productInfoMapper,
                              ProductCategoryMapper categoryMapper,
                              RedisUtil redisUtil,
                              RedissonClient redissonClient) {
        this.productInfoMapper = productInfoMapper;
        this.categoryMapper = categoryMapper;
        this.redisUtil = redisUtil;
        this.redissonClient = redissonClient;
    }

    @Override
    public Page<ProductVO> page(ProductQueryDTO queryDTO) {
        // 分类过滤要**含子分类**：商品只挂在叶子分类上（本项目里全部挂在二级分类），
        // 只按传入的一级分类 id 精确匹配会恒查不到任何商品，
        // 表现为「首页分类入口 / 分类页点进去一片空白」。
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
            redisUtil.set(cacheKey, CACHE_EMPTY, RedisKeyConstant.EMPTY_CACHE_EXPIRE_SECONDS, TimeUnit.SECONDS);
            return null;
        }
        ProductVO vo = toProductVO(product, categoryNameMap());
        redisUtil.setObject(cacheKey, vo, RedisKeyConstant.PRODUCT_DETAIL_EXPIRE_SECONDS);
        return vo;
    }

    @Override
    public ProductVO detailFresh(Long id) {
        ProductInfo product = productInfoMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        return toProductVO(product, categoryNameMap());
    }

    @Override
    public List<ProductVO> listByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<Long, String> categoryNameMap = categoryNameMap();
        return productInfoMapper.selectBatchIds(ids).stream()
                .map(p -> toProductVO(p, categoryNameMap))
                .collect(Collectors.toList());
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

    /**
     * 扣减库存：「Redisson 分布式锁 + 数据库行锁/乐观锁」双层保障
     *
     * <p>必须开启事务：{@code selectByIdForUpdate} 的排他行锁只在事务内持续持有，
     * 否则 autocommit 下语句结束即释放，「当前读」形同虚设（历史缺陷）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public StockDeductResult deductStock(StockDeductDTO dto) {
        String lockKey = "ecommerce:stock:lock:" + dto.getProductId();
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            locked = lock.tryLock(lockWaitSeconds, lockLeaseSeconds, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("获取库存锁超时：productId={}", dto.getProductId());
                return StockDeductResult.fail(ResultCode.ERROR.getCode(), "系统繁忙，请稍后重试");
            }
            return deductInLock(dto);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StockDeductResult.fail(ResultCode.ERROR.getCode(), "系统繁忙，请稍后重试");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 回补库存（取消订单 / 超时取消 / 下单失败补偿），分布式锁内执行
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restoreStock(Long productId, Integer quantity) {
        if (productId == null || quantity == null || quantity <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "库存回补参数不合法");
        }
        String lockKey = "ecommerce:stock:lock:" + productId;
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            locked = lock.tryLock(lockWaitSeconds, lockLeaseSeconds, TimeUnit.SECONDS);
            if (!locked) {
                throw new BusinessException(ResultCode.ERROR, "获取库存锁超时");
            }
            ProductInfo product = productInfoMapper.selectById(productId);
            if (product == null) {
                // 商品已被删除时明确失败，便于订单服务记录补偿失败并重试
                throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
            }
            int rows = productInfoMapper.restoreStock(productId, quantity);
            if (rows == 0) {
                log.warn("库存回补未命中任何行：productId={}", productId);
            }
            // 回补后同步更新缓存，保证数据一致性
            clearCache(productId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ResultCode.ERROR, "库存回补失败");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 锁内执行：商品校验 + 「当前读 + 条件更新」扣减。
     *
     * <p>并发正确性来自两个层面：
     * <ol>
     *   <li>{@code SELECT ... FOR UPDATE} 排他行锁（当前读，绕过 REPEATABLE READ 快照）
     *       ——同一商品的「判断库存 → 扣减」被串行化，这是主要保障；</li>
     *   <li>UPDATE 语句本身再带 {@code stock >= ? AND version = ?} 条件
     *       ——数据库层兜底，即使行锁因异常场景失效也不会把库存扣成负数。</li>
     * </ol>
     * 二者叠加后条件更新不会再因版本冲突失败，故无需重试循环。
     */
    private StockDeductResult deductInLock(StockDeductDTO dto) {
        ProductInfo product = productInfoMapper.selectByIdForUpdate(dto.getProductId());
        if (product == null) {
            return StockDeductResult.fail(ResultCode.PRODUCT_NOT_FOUND.getCode(),
                    ResultCode.PRODUCT_NOT_FOUND.getMessage());
        }
        if (!ProductStatusEnum.isOnShelf(product.getStatus())) {
            return StockDeductResult.fail(ResultCode.PRODUCT_OFF_SHELF.getCode(),
                    "商品【" + product.getName() + "】已下架");
        }
        int quantity = dto.getQuantity() == null ? 0 : dto.getQuantity();
        if (quantity <= 0) {
            return StockDeductResult.fail(ResultCode.PARAM_ERROR.getCode(), "扣减数量必须大于 0");
        }
        if (product.getStock() < quantity) {
            return StockDeductResult.fail(ResultCode.STOCK_NOT_ENOUGH.getCode(),
                    "商品【" + product.getName() + "】库存不足");
        }
        int rows = productInfoMapper.deductStock(product.getId(), quantity, product.getVersion());
        if (rows == 0) {
            // 正常路径不会走到：说明行锁未按预期生效，必须显式失败而不是返回成功
            log.error("库存条件更新未生效（疑似锁失效）：productId={}, version={}",
                    product.getId(), product.getVersion());
            return StockDeductResult.fail(ResultCode.STOCK_CONFLICT.getCode(),
                    ResultCode.STOCK_CONFLICT.getMessage());
        }
        clearCache(product.getId());
        return StockDeductResult.ok(product.getName(), product.getPrice());
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
     * <p>ProductInfo 上有 {@code @Version} 且已注册 OptimisticLockerInnerInterceptor，
     * 版本冲突时 MP 返回 0 行而不抛异常；若不校验就会出现「接口返回成功、数据库没变、
     * 缓存却已清除」的假成功（历史缺陷）。
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
