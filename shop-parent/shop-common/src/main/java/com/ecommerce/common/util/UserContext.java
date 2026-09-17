package com.ecommerce.common.util;

import com.ecommerce.common.constant.AuthConstant;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ResultCode;

/**
 * 用户上下文：登录用户信息在当前请求线程内的持有者。
 *
 * <p>写入来源：外部请求由 {@code UserHeaderInterceptor} 从网关透传的
 * X-User-Id / X-User-Role 读取；服务间 Feign 调用由请求拦截器自动透传。
 *
 * <p>注意：{@link #getUserId()} 可能返回 null（请求未经网关鉴权或属于白名单）。
 * 业务代码需要登录态时请使用 {@link #requireUserId()}，不要直接对 null 做业务判断。
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
     * 取当前登录用户 ID，未登录时抛 401。
     * 用于替代直接使用 {@link #getUserId()}，避免 userId 为 null 时把脏数据写进库。
     */
    public static Long requireUserId() {
        Long userId = USER_ID_HOLDER.get();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    /**
     * 要求管理员身份，否则抛 403。
     * 用于管理端接口（发货 / 完成 / 管理取消 / 经营统计等）。
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
