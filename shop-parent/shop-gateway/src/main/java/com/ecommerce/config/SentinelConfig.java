package com.ecommerce.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiDefinition;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPathPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.BlockRequestHandler;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.ecommerce.common.result.Result;
import com.ecommerce.common.result.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerResponse;

import javax.annotation.PostConstruct;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Sentinel 网关限流配置
 * 1. 下单接口（POST /api/order）单机 QPS 限流
 * 2. 下单接口按来源 IP 访问频率限制
 * 3. 限流后返回统一业务错误码 429
 */
@Slf4j
@Component
public class SentinelConfig {

    private final ObjectMapper objectMapper;

    public SentinelConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Sentinel 网关过滤器（适配器不自动注册，需手动加入过滤器链）。
     *
     * <p>order 显式设为 {@code HIGHEST_PRECEDENCE + 1}：适配器默认值就是
     * {@code HIGHEST_PRECEDENCE}，与 {@code BlockExceptionFilter} 的 {@code Integer.MIN_VALUE} 完全相同，
     * 同序时两者的相对顺序不确定，会导致 BlockExceptionFilter 的
     * {@code onErrorResume(BlockException.class)} 有时包不住 Sentinel 抛出的异常。
     * 让 Sentinel 明确晚于兜底过滤器一步，顺序即可确定。
     */
    @Bean
    public SentinelGatewayFilter sentinelGatewayFilter() {
        return new SentinelGatewayFilter() {
            @Override
            public int getOrder() {
                return Ordered.HIGHEST_PRECEDENCE + 1;
            }
        };
    }

    @PostConstruct
    public void init() {
        initBlockHandler();
        initApiDefinitions();
        initGatewayRules();
        log.info("Sentinel 网关限流规则加载完成");
    }

    /**
     * 限流回调：统一返回 429 业务错误码
     */
    private void initBlockHandler() {
        BlockRequestHandler blockRequestHandler = (exchange, throwable) -> {
            Result<Void> result = Result.error(ResultCode.TOO_MANY_REQUESTS);
            String body;
            try {
                body = objectMapper.writeValueAsString(result);
            } catch (Exception e) {
                body = "{\"code\":429,\"message\":\"请求过于频繁\"}";
            }
            return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body);
        };
        GatewayCallbackManager.setBlockHandler(blockRequestHandler);
    }

    /**
     * API 分组：order-create = 创建订单接口（POST /api/order）
     */
    private void initApiDefinitions() {
        Set<ApiDefinition> apiDefinitions = new HashSet<>();
        apiDefinitions.add(new ApiDefinition("order-create")
                .setPredicateItems(new HashSet<>(Arrays.asList(
                        new ApiPathPredicateItem().setPattern("/api/order")
                                .setMatchStrategy(SentinelGatewayConstants.URL_MATCH_STRATEGY_EXACT)))));
        GatewayApiDefinitionManager.loadApiDefinitions(apiDefinitions);
    }

    /**
     * 限流规则
     */
    private void initGatewayRules() {
        Set<GatewayFlowRule> rules = new HashSet<>();

        // 1. 下单接口单机 QPS 限流：20 QPS
        GatewayFlowRule orderQpsRule = new GatewayFlowRule("order-create")
                .setResourceMode(SentinelGatewayConstants.RESOURCE_MODE_CUSTOM_API_NAME)
                .setCount(20)
                .setIntervalSec(1);
        rules.add(orderQpsRule);

        // 2. 下单接口按来源 IP 限流：单 IP 5 QPS
        GatewayFlowRule orderIpRule = new GatewayFlowRule("order-create")
                .setResourceMode(SentinelGatewayConstants.RESOURCE_MODE_CUSTOM_API_NAME)
                .setCount(5)
                .setIntervalSec(1)
                .setParamItem(new com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayParamFlowItem()
                        .setParseStrategy(SentinelGatewayConstants.PARAM_PARSE_STRATEGY_CLIENT_IP));
        rules.add(orderIpRule);

        GatewayRuleManager.loadRules(rules);
    }
}
