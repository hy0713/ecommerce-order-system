package com.ecommerce.common.util;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis 工具类：基于 StringRedisTemplate + Jackson JSON 序列化
 */
@Slf4j
@Component
public class RedisUtil {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public RedisUtil(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    public void set(String key, String value) {
        stringRedisTemplate.opsForValue().set(key, value);
    }

    public void set(String key, String value, long timeout, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, value, timeout, unit);
    }

    public String get(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    public void setObject(String key, Object value) {
        setObject(key, value, null, null);
    }

    /**
     * 缓存对象，可指定过期时间（秒）
     */
    public void setObject(String key, Object value, Long timeoutSeconds) {
        setObject(key, value, timeoutSeconds, TimeUnit.SECONDS);
    }

    private void setObject(String key, Object value, Long timeout, TimeUnit unit) {
        try {
            String json = objectMapper.writeValueAsString(value);
            if (timeout != null && unit != null) {
                set(key, json, timeout, unit);
            } else {
                set(key, json);
            }
        } catch (JsonProcessingException e) {
            log.error("Redis 序列化失败，key={}", key, e);
        }
    }

    /**
     * 读取缓存对象
     */
    public <T> T getObject(String key, Class<T> clazz) {
        String json = get(key);
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.error("Redis 反序列化失败，key={}", key, e);
            return null;
        }
    }

    /**
     * 读取缓存列表对象
     */
    public <T> List<T> getList(String key, Class<T> clazz) {
        String json = get(key);
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            JavaType type = objectMapper.getTypeFactory().constructCollectionType(List.class, clazz);
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.error("Redis 反序列化失败，key={}", key, e);
            return null;
        }
    }

    public void delete(String key) {
        stringRedisTemplate.delete(key);
    }

    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
    }

    /**
     * SETNX 语义：key 不存在时写入并返回 true，已存在返回 false（幂等标记用）
     */
    public boolean setIfAbsent(String key, String value, long timeout, TimeUnit unit) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                .setIfAbsent(key, value, timeout, unit));
    }
}
