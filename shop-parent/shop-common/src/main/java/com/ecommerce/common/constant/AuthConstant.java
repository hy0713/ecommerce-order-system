package com.ecommerce.common.constant;

import org.springframework.util.AntPathMatcher;

/**
 * 认证与授权常量（网关白名单 / 内部调用令牌 / 用户上下文透传）
 *
 * <p>安全约定（改动前务必阅读）：
 * <ol>
 *   <li>匿名白名单按「HTTP 方法 + 路径」精确匹配，<b>禁止使用 <code>/api/xxx/**</code>
 *       这类宽通配覆盖携带写接口的控制器</b>——历史上 <code>/api/product/**</code>
 *       曾把商品改价、改库存、上下架、删除以及库存扣减接口一并放行。</li>
 *   <li>下游业务服务只接受携带内部令牌的请求，防止绕过网关直连服务端口伪造身份。</li>
 *   <li>端点分三类：{@link #PUBLIC_ENDPOINTS 匿名}、
 *       {@link #OPTIONAL_AUTH_ENDPOINTS 可选鉴权（登录可选）}、
 *       其余为<b>必须登录</b>。<b>新增 Python / 非 Java 服务时优先接入网关</b>，
 *       不要让前端直连未鉴权的独立端口。</li>
 * </ol>
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

    /**
     * 内部调用令牌请求头。仅在「网关 → 业务服务」与「服务间 Feign 调用」时携带，
     * 用于拒绝绕开网关的直连请求（防 X-User-Id 伪造）
     */
    public static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    /** 角色：管理员 */
    public static final String ROLE_ADMIN = "ADMIN";

    /** 角色：普通用户 */
    public static final String ROLE_USER = "USER";

    /**
     * 匿名可访问端点，格式为 "HTTP方法 路径"，方法为 {@code *} 表示任意方法。
     *
     * <p>新增白名单必须遵循最小授权：只放行确需匿名访问的<b>只读</b>接口，
     * 且不要用 /** 覆盖整个控制器前缀。
     */
    public static final String[] PUBLIC_ENDPOINTS = {
            // 认证：登录 / 注册（登出需要携带 token，不在白名单）
            "POST /api/auth/login",
            "POST /api/user/register",
            // 商品只读：分页 / 详情 / 实时详情（/fresh 供 AI 客服白名单查询库存）
            "GET /api/product/page",
            "GET /api/product/ids",
            "GET /api/product/*",
            "GET /api/product/*/fresh",
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
     *
     * <p>语义与白名单的区别：**没有 token 也能访问（按匿名处理），但带了 token 就必须有效**。
     * 带无效 token 仍然拒绝，是为了不把「token 过期」伪装成「游客」，
     * 否则前端永远收不到 401、无法提示重新登录。
     *
     * <p>目前只有 AI 客服对话：游客要能问知识类问题，登录用户要能查到自己的订单、延续自己的会话。
     * 会话管理类接口（会话列表 / 历史消息 / 删除）不在其中——那些**必须登录**，
     * 否则 user_id 只能由调用方自报，等于任意用户可读他人聊天记录。
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
     *
     * <p>调用顺序上应在 {@link #isPublic} **之后**判断——
     * 白名单端点即使带了 token 也无需校验，不必走这条更重的分支。
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

    /**
     * 内部接口路径前缀：只允许服务间调用，网关不路由、外网不可达
     */
    public static final String INTERNAL_PATH_PREFIX = "/api/internal/";

    /** 是否内部接口 */
    public static boolean isInternalPath(String path) {
        return path != null && path.startsWith(INTERNAL_PATH_PREFIX);
    }

    /**
     * 路径中是否含「当前目录 / 上级目录」段（{@code .} 或 {@code ..}）。
     *
     * <p>网关必须在路由之前拒绝这类请求：
     * {@code /api/product/../internal/stock/deduct} 不以 {@code /api/internal/} 开头，
     * 绕过了 {@link #isInternalPath} 的拦截，却能命中 {@code /api/product/**} 的路由被原样转发，
     * 下游 Tomcat 归一化路径后即可触达内部接口。
     *
     * <p>传解析后的路径（{@code getPath()}）即可覆盖 {@code %2e%2e} 这类编码写法。
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
