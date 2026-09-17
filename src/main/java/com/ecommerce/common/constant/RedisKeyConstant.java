package com.ecommerce.common.constant;

/**
 * Redis Key 常量
 */
public final class RedisKeyConstant {

    private RedisKeyConstant() {
    }

    /** 登录 token 前缀：token:{jwt} -> userId */
    public static final String TOKEN_PREFIX = "ecommerce:token:";

    /** 登录 token 过期时间（秒）：24 小时 */
    public static final long TOKEN_EXPIRE_SECONDS = 24 * 60 * 60L;

    /** 商品详情缓存前缀：product:detail:{id} */
    public static final String PRODUCT_DETAIL_PREFIX = "ecommerce:product:detail:";

    /** 商品详情缓存过期时间（秒）：30 分钟 */
    public static final long PRODUCT_DETAIL_EXPIRE_SECONDS = 30 * 60L;

    /** 空值缓存过期时间（秒）：5 分钟，防缓存穿透 */
    public static final long EMPTY_CACHE_EXPIRE_SECONDS = 5 * 60L;
}
