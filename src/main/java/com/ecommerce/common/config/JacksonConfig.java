package com.ecommerce.common.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Jackson 配置：java.time 类型统一输出 yyyy-MM-dd HH:mm:ss；雪花 ID 以字符串输出
 */
@Configuration
public class JacksonConfig {

    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    private static final String DATE_PATTERN = "yyyy-MM-dd";

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> {
            DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern(DATE_PATTERN);
            builder.serializers(new LocalDateTimeSerializer(dateTimeFormatter), new LocalDateSerializer(dateFormatter));
            builder.deserializers(new LocalDateTimeDeserializer(dateTimeFormatter), new LocalDateDeserializer(dateFormatter));
        };
    }

    /**
     * 雪花 ID（Long）序列化为字符串。
     *
     * <p><b>为什么必须这么做</b>：雪花算法生成的是 19 位数字，远超 JavaScript 的
     * {@code Number.MAX_SAFE_INTEGER}（2^53-1 ≈ 9007199254740991，16 位）。
     * 前端 {@code JSON.parse} 会把 {@code 2098249056107450369} 变成
     * {@code 2098249056107450400}，再把该值回传到后端就查不到数据——
     * 表现为「模拟下单报收货地址不存在」「取消订单报订单不存在」等。
     *
     * <p><b>只注册包装类型 {@code Long}，不注册基本类型 {@code long}</b>：
     * MyBatis-Plus {@code Page} 的 {@code total/current/size} 是基本类型 long，
     * 若一并转成字符串会破坏前端分页组件；基本类型不注册即可保持为数字。
     *
     * <p>反序列化方向无需特殊处理：Jackson 能自动把 JSON 字符串转回 Long。
     */
    @Bean
    public SimpleModule snowflakeIdModule() {
        SimpleModule module = new SimpleModule("snowflakeIdToString");
        module.addSerializer(Long.class, ToStringSerializer.instance);
        return module;
    }
}
