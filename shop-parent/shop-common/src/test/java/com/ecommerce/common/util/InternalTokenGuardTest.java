package com.ecommerce.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内部调用令牌守卫测试。
 *
 * <p>重点锁住「留空不得退化成跳过校验」这一条：
 * 一旦有人把空值改成"仅告警然后放行"，业务服务的 X-Internal-Token 校验就会静默失效，
 * 直连服务端口伪造 X-User-Id 即可冒充任意用户（含管理员）。
 */
@DisplayName("内部调用令牌配置守卫")
class InternalTokenGuardTest {

    @Test
    @DisplayName("令牌缺失（null / 空串 / 纯空白）必须拒绝启动")
    void blankTokenFailsFast() {
        assertThrows(IllegalStateException.class, () -> InternalTokenGuard.validate(null, "网关"));
        assertThrows(IllegalStateException.class, () -> InternalTokenGuard.validate("", "网关"));
        assertThrows(IllegalStateException.class, () -> InternalTokenGuard.validate("   ", "业务服务"));
    }

    @Test
    @DisplayName("错误信息要指明怎么修（环境变量名 + 后果）")
    void messageIsActionable() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> InternalTokenGuard.validate(null, "网关"));
        String msg = e.getMessage();
        assertTrue(msg.contains("INTERNAL_TOKEN"), "应提示环境变量名：" + msg);
        assertTrue(msg.contains("ecommerce.internal.token"), "应提示配置项名：" + msg);
        assertTrue(msg.contains("X-User-Id"), "应说明后果：" + msg);
        assertTrue(msg.contains("网关"), "应带上调用方名称便于定位：" + msg);
    }

    @Test
    @DisplayName("已配置则正常放行（不校验长度，令牌由部署方自行保证强度）")
    void configuredTokenPasses() {
        assertDoesNotThrow(() -> InternalTokenGuard.validate("local-dev-change-me-internal-token", "网关"));
        assertDoesNotThrow(() -> InternalTokenGuard.validate("x", "业务服务"));
    }
}
