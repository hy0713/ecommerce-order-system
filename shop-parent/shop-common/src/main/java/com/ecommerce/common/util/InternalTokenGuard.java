package com.ecommerce.common.util;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 内部调用令牌（{@code X-Internal-Token}）配置校验。
 *
 * <p><b>为什么必须 fail-fast，而不是"留空就跳过校验"：</b>
 * 这个令牌是业务服务判断「请求确实来自网关」的**唯一依据**（见
 * {@link com.ecommerce.common.interceptor.UserHeaderInterceptor}）。
 * 而下游对 {@code X-User-Id} / {@code X-User-Role} 是完全信任的，
 * 所以一旦校验被跳过，任何人直连业务服务端口（8081/8082/8083）
 * 伪造一个 {@code X-User-Id} 就能冒充任意用户——**包括管理员**。
 * 留空等于静默打开一个越权入口，因此这里直接拒绝启动。
 *
 * <p><b>为什么抽成共用类：</b>网关与业务服务两侧都要用同一个令牌，
 * 历史上 JWT 工具就因为"单体与微服务各一份副本"出现过
 * 「一处有守卫、另一处完全没有」的漂移。这里只允许一份实现，从结构上避免重演。
 */
@Slf4j
public final class InternalTokenGuard {

    private InternalTokenGuard() {
    }

    /**
     * 校验内部调用令牌配置，缺失即拒绝启动。
     *
     * @param token     配置项 {@code ecommerce.internal.token}（环境变量 {@code INTERNAL_TOKEN}）
     * @param component 调用方名称，用于错误信息定位
     */
    public static void validate(String token, String component) {
        if (StrUtil.isBlank(token)) {
            throw new IllegalStateException(component + " 未配置内部调用令牌："
                    + "请设置环境变量 INTERNAL_TOKEN（或配置项 ecommerce.internal.token）。"
                    + "留空会使业务服务跳过 X-Internal-Token 校验，"
                    + "直连服务端口即可伪造 X-User-Id 冒充任意用户（含管理员）。"
                    + "本地演示由 start-all.sh 自动注入；单独启动某个服务时请自行 export INTERNAL_TOKEN=...");
        }
        // 只记长度，绝不打印令牌本身
        log.info("{} 内部调用令牌已配置（长度 {}）", component, token.length());
    }
}
