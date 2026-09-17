package com.ecommerce.common.util;

import com.ecommerce.common.constant.AuthConstant;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ResultCode;

/**
 * 用户上下文：登录用户信息在当前请求线程内的持有者。
 * 由 JwtAuthInterceptor 在请求进入时写入，Controller/Service 中读取。
 *
 * <p>注意：{@link #getUserId()} 可能返回 null（匿名白名单接口）。
 * 业务代码需要登录态时请使用 {@link #requireUserId()}。
 */
public final class UserContext {

    private UserContext() {
    }

    private static final ThreadLocal<Long> USER_ID_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE_HOLDER = new ThreadLocal<>();

    public static void setUserId(Long userId) {
        USER_ID_HOLDER.set(userId);
    }

    public static Long getUserId() {
        return USER_ID_HOLDER.get();
    }

    public static void setRole(String role) {
        ROLE_HOLDER.set(role);
    }

    public static String getRole() {
        return ROLE_HOLDER.get();
    }

    /** 是否管理员 */
    public static boolean isAdmin() {
        return AuthConstant.ROLE_ADMIN.equals(ROLE_HOLDER.get());
    }

    /**
     * 取当前登录用户 ID，未登录时抛 401，避免 userId 为 null 时写脏数据
     */
    public static Long requireUserId() {
        Long userId = USER_ID_HOLDER.get();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    /**
     * 要求管理员身份，否则抛 403
     */
    public static void requireAdmin() {
        requireUserId();
        if (!isAdmin()) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }

    public static void clear() {
        USER_ID_HOLDER.remove();
        ROLE_HOLDER.remove();
    }
}
