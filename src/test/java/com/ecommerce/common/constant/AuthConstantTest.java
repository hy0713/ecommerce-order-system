package com.ecommerce.common.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 匿名白名单匹配规则防回归测试。
 *
 * <p>这些断言保护的是历史越权漏洞的修复结果：曾经 {@code /api/product/**}
 * 把商品改价、改库存、上下架、删除以及库存扣减接口全部放行。
 * 任何人重构白名单时若重新引入宽通配，本测试会立刻失败。
 */
class AuthConstantTest {

    @Test
    @DisplayName("登录/注册可匿名访问")
    void authEndpointsArePublic() {
        assertTrue(AuthConstant.isPublic("POST", "/api/auth/login"));
        assertTrue(AuthConstant.isPublic("POST", "/api/user/register"));
    }

    @Test
    @DisplayName("商品与分类的只读接口可匿名访问")
    void readOnlyCatalogEndpointsArePublic() {
        assertTrue(AuthConstant.isPublic("GET", "/api/product/page"));
        assertTrue(AuthConstant.isPublic("GET", "/api/product/1"));
        assertTrue(AuthConstant.isPublic("GET", "/api/category/tree"));
    }

    @Test
    @DisplayName("商品写接口必须鉴权（改价/改库存/上下架/删除）")
    void productWriteEndpointsRequireAuth() {
        assertFalse(AuthConstant.isPublic("POST", "/api/product"));
        assertFalse(AuthConstant.isPublic("PUT", "/api/product/1"));
        assertFalse(AuthConstant.isPublic("PUT", "/api/product/1/status"));
        assertFalse(AuthConstant.isPublic("DELETE", "/api/product/1"));
    }

    @Test
    @DisplayName("分类写接口必须鉴权")
    void categoryWriteEndpointsRequireAuth() {
        assertFalse(AuthConstant.isPublic("POST", "/api/category"));
        assertFalse(AuthConstant.isPublic("PUT", "/api/category/1"));
        assertFalse(AuthConstant.isPublic("DELETE", "/api/category/1"));
    }

    @Test
    @DisplayName("库存扣减/回补为内部接口，绝不可匿名访问")
    void stockEndpointsAreNotPublic() {
        assertFalse(AuthConstant.isPublic("POST", "/api/product/stock/deduct"));
        assertFalse(AuthConstant.isPublic("POST", "/api/product/stock/restore"));
        assertFalse(AuthConstant.isPublic("POST", "/api/internal/stock/deduct"));
        assertFalse(AuthConstant.isPublic("POST", "/api/internal/stock/restore"));
        assertTrue(AuthConstant.isInternalPath("/api/internal/stock/deduct"));
    }

    @Test
    @DisplayName("订单/购物车/地址接口必须鉴权")
    void userScopedEndpointsRequireAuth() {
        assertFalse(AuthConstant.isPublic("POST", "/api/order"));
        assertFalse(AuthConstant.isPublic("POST", "/api/order/1/cancel"));
        assertFalse(AuthConstant.isPublic("POST", "/api/order/1/admin-cancel"));
        assertFalse(AuthConstant.isPublic("GET", "/api/order/stats"));
        assertFalse(AuthConstant.isPublic("GET", "/api/cart/list"));
        assertFalse(AuthConstant.isPublic("POST", "/api/auth/logout"));
    }

    @Test
    @DisplayName("OPTIONS 预检请求放行，避免阻断跨域")
    void optionsAlwaysAllowed() {
        assertTrue(AuthConstant.isPublic("OPTIONS", "/api/order"));
        assertTrue(AuthConstant.isPublic("OPTIONS", "/api/product"));
    }

    @Test
    @DisplayName("接口文档路径可匿名访问")
    void docEndpointsArePublic() {
        assertTrue(AuthConstant.isPublic("GET", "/doc.html"));
        assertTrue(AuthConstant.isPublic("GET", "/v3/api-docs/group"));
        assertTrue(AuthConstant.isPublic("GET", "/webjars/js/app.js"));
    }

    @Test
    @DisplayName("空值与非法输入不抛异常且不误放行")
    void nullAndMalformedInputAreNotPublic() {
        assertFalse(AuthConstant.isPublic(null, "/api/order"));
        assertFalse(AuthConstant.isPublic("GET", null));
        assertFalse(AuthConstant.isPublic("GET", "/api/unknown"));
    }

    @Test
    @DisplayName("路径穿越段必须被识别（否则可绕到 /api/internal 内部接口）")
    void dotSegmentsAreDetected() {
        // 这条路径不以 /api/internal/ 开头，能绕过 isInternalPath 的前缀判断，
        // 却能被 /api/product/** 路由转发，下游 Tomcat 归一化后即可触达内部库存接口
        assertTrue(AuthConstant.hasDotSegment("/api/product/../internal/stock/deduct"));
        assertFalse(AuthConstant.isInternalPath("/api/product/../internal/stock/deduct"));
        // 其他形式
        assertTrue(AuthConstant.hasDotSegment("/api/./internal/stock/restore"));
        assertTrue(AuthConstant.hasDotSegment("/api/product/.."));
        assertTrue(AuthConstant.hasDotSegment("/../api/product/page"));
    }

    @Test
    @DisplayName("正常路径不得被路径穿越规则误伤")
    void normalPathsAreNotTreatedAsTraversal() {
        assertFalse(AuthConstant.hasDotSegment("/api/product/page"));
        assertFalse(AuthConstant.hasDotSegment("/api/product/1"));
        // 含点但不是独立路径段：版本号、文件名等
        assertFalse(AuthConstant.hasDotSegment("/v3/api-docs/group"));
        assertFalse(AuthConstant.hasDotSegment("/doc.html"));
        assertFalse(AuthConstant.hasDotSegment("/api/product/1.5"));
        assertFalse(AuthConstant.hasDotSegment(null));
        assertFalse(AuthConstant.hasDotSegment(""));
    }

    @Test
    @DisplayName("Agent 对话为「可选鉴权」：匿名可用，但不算白名单")
    void agentChatIsOptionalAuth() {
        assertTrue(AuthConstant.isOptionalAuth("POST", "/api/agent/chat"));
        // 可选鉴权不等于白名单：白名单会跳过 token 校验，语义不同
        assertFalse(AuthConstant.isPublic("POST", "/api/agent/chat"));
    }

    @Test
    @DisplayName("Agent 会话接口必须登录：不允许匿名，也不允许可选鉴权")
    void agentSessionEndpointsRequireAuth() {
        // 这四个接口涉及他人会话数据，user_id 只能来自网关注入，绝不能匿名可读
        assertFalse(AuthConstant.isPublic("GET", "/api/agent/session/list"));
        assertFalse(AuthConstant.isOptionalAuth("GET", "/api/agent/session/list"));
        assertFalse(AuthConstant.isPublic("POST", "/api/agent/session/create"));
        assertFalse(AuthConstant.isOptionalAuth("POST", "/api/agent/session/create"));
        assertFalse(AuthConstant.isPublic("DELETE", "/api/agent/session/delete/1"));
        assertFalse(AuthConstant.isPublic("GET", "/api/agent/session/1/messages"));
        assertFalse(AuthConstant.isOptionalAuth("GET", "/api/agent/session/1/messages"));
        // 知识库与工具配置属管理动作：网关侧只保证「已登录」，
        // 「必须是管理员」由 Agent 侧 required_admin 依据网关注入的 X-User-Role 判定
        assertFalse(AuthConstant.isPublic("POST", "/api/agent/knowledge/upload"));
        assertFalse(AuthConstant.isOptionalAuth("POST", "/api/agent/knowledge/upload"));
        assertFalse(AuthConstant.isPublic("DELETE", "/api/agent/knowledge/delete/1"));
        assertFalse(AuthConstant.isPublic("PUT", "/api/agent/tool/status/1"));
        assertFalse(AuthConstant.isOptionalAuth("PUT", "/api/agent/tool/status/1"));
    }

    @Test
    @DisplayName("可选鉴权不得泛化到其他接口")
    void optionalAuthIsNotOverBroad() {
        assertFalse(AuthConstant.isOptionalAuth("POST", "/api/product"));
        assertFalse(AuthConstant.isOptionalAuth("PUT", "/api/product/1"));
        assertFalse(AuthConstant.isOptionalAuth("POST", "/api/order"));
        assertFalse(AuthConstant.isOptionalAuth("GET", "/api/user/info"));
        assertFalse(AuthConstant.isOptionalAuth("PUT", "/api/agent/chat"));
    }

    @Test
    @DisplayName("Agent 健康检查为白名单，但仅限只读探活")
    void agentHealthIsPublic() {
        assertTrue(AuthConstant.isPublic("GET", "/api/agent/health"));
        assertFalse(AuthConstant.isOptionalAuth("GET", "/api/agent/health"));
    }
}
