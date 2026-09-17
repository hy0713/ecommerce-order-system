package com.ecommerce.service;

import com.ecommerce.common.dto.LoginDTO;
import com.ecommerce.common.dto.RegisterDTO;
import com.ecommerce.common.feign.TokenValidateResult;
import com.ecommerce.common.vo.LoginVO;
import com.ecommerce.common.vo.UserVO;

/**
 * 用户服务
 */
public interface UserService {

    /**
     * 注册
     */
    void register(RegisterDTO registerDTO);

    /**
     * 登录：生成 JWT，token 存入 Redis
     */
    LoginVO login(LoginDTO loginDTO);

    /**
     * 查询用户信息
     */
    UserVO getUserInfo(Long userId);

    /**
     * 退出登录：使 Redis 中 token 失效
     */
    void logout(String token);

    /**
     * 校验 token 有效性（网关调用）：验签 + Redis 校验
     */
    TokenValidateResult validateToken(String token);
}
