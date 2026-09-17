package com.ecommerce.service;

import com.ecommerce.dto.LoginDTO;
import com.ecommerce.dto.RegisterDTO;
import com.ecommerce.vo.LoginVO;
import com.ecommerce.vo.UserVO;

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
}
