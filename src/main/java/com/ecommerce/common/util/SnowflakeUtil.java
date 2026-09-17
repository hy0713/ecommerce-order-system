package com.ecommerce.common.util;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 雪花算法工具：生成分布式唯一 ID。
 *
 * <p>workerId / datacenterId <b>不再写死为 (1, 1)</b>：那样所有服务、所有实例共用同一组机器位，
 * 同一毫秒内两个实例生成的 ID 会完全重复 —— 对「同一服务的多实例」而言，这会直接造成
 * order_no / 主键的唯一键冲突。现在改为：
 * <ul>
 *   <li>优先读环境变量 {@code SNOWFLAKE_WORKER_ID} / {@code SNOWFLAKE_DATACENTER_ID}（生产建议显式注入）；</li>
 *   <li>否则 workerId 取当前进程号低位、datacenterId 取「主机名 + 本机地址」哈希低位。</li>
 * </ul>
 * 同机多实例因进程号不同而区分，跨机部署因地址不同而区分。
 *
 * <p>注意：workerId / datacenterId 各只有 32 个取值，理论碰撞无法根除；数据库唯一键
 * 仍是最后一道防线（本项目 order_no 与各表主键均带唯一约束）。
 */
@Slf4j
public final class SnowflakeUtil {

    /** Hutool 的 workerId / datacenterId 取值范围为 0..31 */
    private static final long NODE_ID_MASK = 31L;

    private static final Snowflake SNOWFLAKE = createSnowflake();

    private SnowflakeUtil() {
    }

    private static Snowflake createSnowflake() {
        long workerId = resolveWorkerId();
        long datacenterId = resolveDatacenterId();
        log.info("Snowflake 初始化：workerId={}, datacenterId={}", workerId, datacenterId);
        return IdUtil.getSnowflake(workerId, datacenterId);
    }

    /** workerId：环境变量优先，否则取当前进程号低位（同机多实例据此区分） */
    private static long resolveWorkerId() {
        Long fromEnv = readEnv("SNOWFLAKE_WORKER_ID");
        if (fromEnv != null) {
            return fromEnv & NODE_ID_MASK;
        }
        long pid;
        try {
            pid = ProcessHandle.current().pid();
        } catch (Exception e) {
            pid = ThreadLocalRandom.current().nextLong();
        }
        return pid & NODE_ID_MASK;
    }

    /** datacenterId：环境变量优先，否则取「主机名 + 本机地址」哈希低位（跨机部署据此区分） */
    private static long resolveDatacenterId() {
        Long fromEnv = readEnv("SNOWFLAKE_DATACENTER_ID");
        if (fromEnv != null) {
            return fromEnv & NODE_ID_MASK;
        }
        long hash;
        try {
            InetAddress local = InetAddress.getLocalHost();
            hash = (local.getHostName() + '|' + local.getHostAddress()).hashCode();
        } catch (Exception e) {
            log.warn("获取本机地址失败，datacenterId 退化为随机值：{}", e.getMessage());
            hash = ThreadLocalRandom.current().nextLong();
        }
        return hash & NODE_ID_MASK;
    }

    private static Long readEnv(String name) {
        String raw = System.getenv(name);
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("环境变量 {} 非法（{}），已忽略", name, raw);
            return null;
        }
    }

    /** 生成 long 型唯一 ID（表主键） */
    public static long nextId() {
        return SNOWFLAKE.nextId();
    }

    /** 生成字符串型唯一 ID（订单号） */
    public static String nextIdStr() {
        return SNOWFLAKE.nextIdStr();
    }
}
