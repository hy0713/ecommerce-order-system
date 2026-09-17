package com.ecommerce.common.constant;

import org.springframework.util.AntPathMatcher;

/**
 * 认证与授权常量。
 *
 * <p>安全约定（改动前务必阅读）：
 * 匿名白名单按「HTTP 方法 + 路径」精确匹配，<b>禁止使用 <code>/api/xxx/**</code>
 * 这类宽通配覆盖携带写接口的控制器</b>——历史上 <code>/api/product/**</code> 曾把
 * 商品改价、改库存、上下架、删除等写接口一并放行。
 *
 * <p>本类与微服务版 shop-common 的同名类保持内容一致，修改时请同步。
 */
public final class AuthConstant {

    private AuthConstant() {
    }

    /** 请求头 Authorization */
    public static final String HEADER_AUTHORIZATION = "Authorization";

    /** Bearer 前缀 */
    public static final String BEARER_PREFIX = "Bearer ";

    /** 网关校验通过后透传的用户 ID 请求头 */
    public static final String HEADER_USER_ID = "X-User-Id";

    /** 网关校验通过后透传的用户角色请求头 */
    public static final String HEADER_USER_ROLE = "X-User-Role";

    /** 内部调用令牌请求头（单体部署不使用，保留以与微服务版保持一致） */
    public static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    /** 角色：管理员 */
    public static final String ROLE_ADMIN = "ADMIN";

    /** 角色：普通用户 */
    public static final String ROLE_USER = "USER";

    /**
     * 匿名可访问端点，格式为 "HTTP方法 路径"，方法为 {@code *} 表示任意方法。
     */
    public static final String[] PUBLIC_ENDPOINTS = {
            // 认证：登录 / 注册（登出需要携带 token，不在白名单）
            "POST /api/auth/login",
            "POST /api/user/register",
            // 商品只读：分页 / 详情
            "GET /api/product/page",
            "GET /api/product/*",
            // 分类只读：分类树
            "GET /api/category/tree",
            // AI 客服健康检查（探活/监控用，只读且不暴露业务数据）
            "GET /api/agent/health",
            // 接口文档
            "GET /doc.html",
            "GET /webjars/**",
            "GET /v3/api-docs/**",
            "GET /swagger-ui/**",
            "GET /swagger-ui.html",
            "GET /favicon.ico"
    };

    /**
     * 「可选鉴权」端点，格式同 {@link #PUBLIC_ENDPOINTS}。
     * 没有 token 也能访问（按匿名处理），但带了 token 就必须有效。
     * 单体部署没有网关路由到 AI 客服，本表保留以与微服务版内容一致。
     */
    public static final String[] OPTIONAL_AUTH_ENDPOINTS = {
            "POST /api/agent/chat"
    };

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /**
     * 判断请求是否可匿名访问
     *
     * @param method HTTP 方法（GET/POST/PUT/DELETE/OPTIONS...）
     * @param path   请求路径（不含查询串）
     */
    public static boolean isPublic(String method, String path) {
        return matches(PUBLIC_ENDPOINTS, method, path);
    }

    /**
     * 判断请求是否为「可选鉴权」：匿名可访问，但携带 token 时必须有效。
     * 应在 {@link #isPublic} 之后判断。
     */
    public static boolean isOptionalAuth(String method, String path) {
        return matches(OPTIONAL_AUTH_ENDPOINTS, method, path);
    }

    /**
     * 按「HTTP 方法 + 路径」精确匹配端点表。方法为 {@code *} 表示任意方法。
     * OPTIONS 预检请求一律放行，避免阻断 CORS。
     */
    private static boolean matches(String[] endpoints, String method, String path) {
        if (method == null || path == null) {
            return false;
        }
        // 预检请求不做鉴权，避免阻断 CORS
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        for (String entry : endpoints) {
            int sep = entry.indexOf(' ');
            if (sep <= 0 || sep == entry.length() - 1) {
                continue;
            }
            String entryMethod = entry.substring(0, sep);
            String entryPath = entry.substring(sep + 1);
            if (!"*".equals(entryMethod) && !entryMethod.equalsIgnoreCase(method)) {
                continue;
            }
            if (PATH_MATCHER.match(entryPath, path)) {
                return true;
            }
        }
        return false;
    }

    /** 内部接口路径前缀（与微服务版保持一致） */
    public static final String INTERNAL_PATH_PREFIX = "/api/internal/";

    /** 是否内部接口 */
    public static boolean isInternalPath(String path) {
        return path != null && path.startsWith(INTERNAL_PATH_PREFIX);
    }

    /**
     * 路径中是否含「当前目录 / 上级目录」段（{@code .} 或 {@code ..}）。
     *
     * <p>与微服务版保持一致：网关必须在路由之前拒绝这类请求，
     * 否则 {@code /api/product/../internal/stock/deduct} 不以 {@code /api/internal/} 开头，
     * 绕过 {@link #isInternalPath} 后仍能被 {@code /api/product/**} 路由转发，
     * 下游 Tomcat 归一化路径即可触达内部接口。
     */
    public static boolean hasDotSegment(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        for (String segment : path.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }
}
