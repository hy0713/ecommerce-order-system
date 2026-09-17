package com.ecommerce.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.common.constant.AuthConstant;
import com.ecommerce.common.constant.RedisKeyConstant;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ResultCode;
import com.ecommerce.common.util.JwtUtil;
import com.ecommerce.common.util.RedisUtil;
import com.ecommerce.dto.LoginDTO;
import com.ecommerce.dto.RegisterDTO;
import com.ecommerce.entity.User;
import com.ecommerce.mapper.UserMapper;
import com.ecommerce.service.UserService;
import com.ecommerce.vo.LoginVO;
import com.ecommerce.vo.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

/**
 * 用户服务实现
 */
@Slf4j
@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final RedisUtil redisUtil;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserServiceImpl(UserMapper userMapper, JwtUtil jwtUtil, RedisUtil redisUtil) {
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
        this.redisUtil = redisUtil;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterDTO registerDTO) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, registerDTO.getUsername()));
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.USERNAME_EXISTS);
        }
        User user = new User();
        user.setUsername(registerDTO.getUsername());
        // 密码 BCrypt 加密存储，禁止明文
        user.setPassword(passwordEncoder.encode(registerDTO.getPassword()));
        user.setPhone(registerDTO.getPhone());
        user.setStatus(1);
        // 自助注册一律为普通用户，管理员只能由初始化脚本/后台授予
        user.setRole(AuthConstant.ROLE_USER);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发注册同名用户：靠唯一索引兜底并给出明确提示
            throw new BusinessException(ResultCode.USERNAME_EXISTS);
        }
        log.info("用户注册成功：userId={}, username={}", user.getId(), user.getUsername());
    }

    @Override
    public LoginVO login(LoginDTO loginDTO) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, loginDTO.getUsername()));
        // 用户不存在或密码错误统一提示，避免账号枚举
        if (user == null || !passwordEncoder.matches(loginDTO.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.LOGIN_FAILED);
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(ResultCode.USER_DISABLED);
        }
        // 生成 JWT（含角色声明）并写入 Redis，设置过期时间
        String role = StrUtil.blankToDefault(user.getRole(), AuthConstant.ROLE_USER);
        String token = jwtUtil.generateToken(user.getId(), role);
        redisUtil.set(RedisKeyConstant.TOKEN_PREFIX + token, String.valueOf(user.getId()),
                RedisKeyConstant.TOKEN_EXPIRE_SECONDS, TimeUnit.SECONDS);
        log.info("用户登录成功：userId={}, role={}", user.getId(), role);
        return new LoginVO(token, toUserVO(user));
    }

    @Override
    public UserVO getUserInfo(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return toUserVO(user);
    }

    @Override
    public void logout(String token) {
        redisUtil.delete(RedisKeyConstant.TOKEN_PREFIX + token);
    }

    private UserVO toUserVO(User user) {
        // BeanUtil 仅复制匹配字段，UserVO 无 password 字段，天然避免密码返回
        return BeanUtil.copyProperties(user, UserVO.class);
    }
}
