package com.ecommerce.common.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ecommerce.common.constant.AuthConstant;
import com.ecommerce.entity.User;
import com.ecommerce.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 启动数据初始化：默认管理员账号不存在时创建（密码 BCrypt 加密）。
 *
 * <p>默认账号：admin / 123456（角色 ADMIN）。自助注册的账号一律为 USER。
 *
 * <p>容错：数据库未就绪时按固定间隔重试，重试耗尽后只记录错误、<b>不再让进程退出</b>。
 * 历史行为是此处异常直接终止应用（日志里已出现 Tomcat 监听 8080 + Started，
 * 随后进程退出），在容器编排并发启动时表现为随机启动失败。
 */
@Slf4j
@Component
public class DataInitializer implements ApplicationRunner {

    /** 最大尝试次数（约 2s × 5 ≈ 10s，仅用于覆盖「数据库比应用晚起几秒」的场景） */
    private static final int MAX_ATTEMPTS = 5;
    /** 重试间隔（毫秒） */
    private static final long RETRY_INTERVAL_MS = 2000L;

    private final UserMapper userMapper;

    public DataInitializer(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                initAdmin();
                return;
            } catch (Exception e) {
                log.warn("启动初始化第 {}/{} 次失败：{}", attempt, MAX_ATTEMPTS, e.getMessage());
                if (attempt == MAX_ATTEMPTS) {
                    log.error("启动初始化多次失败，跳过默认管理员创建，应用继续启动；"
                            + "请检查数据库连接后重启或手工创建 admin 账号", e);
                    return;
                }
                sleepQuietly();
            }
        }
    }

    private void initAdmin() {
        User existing = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, "admin"));
        if (existing == null) {
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(new BCryptPasswordEncoder().encode("123456"));
            admin.setPhone("13800000000");
            admin.setStatus(1);
            admin.setRole(AuthConstant.ROLE_ADMIN);
            try {
                userMapper.insert(admin);
                log.info("默认管理员账号已创建：admin / 123456（角色 ADMIN）");
            } catch (DuplicateKeyException e) {
                log.info("默认管理员账号已存在，跳过创建");
            }
            return;
        }
        // 兼容升级：历史库中 admin 的 role 为空，补一次，避免上线后管理员变成普通用户
        if (existing.getRole() == null || existing.getRole().isEmpty()) {
            userMapper.update(null, new LambdaUpdateWrapper<User>()
                    .eq(User::getId, existing.getId())
                    .set(User::getRole, AuthConstant.ROLE_ADMIN));
            log.warn("已为存量 admin 账号补全角色：userId={}", existing.getId());
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(RETRY_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
