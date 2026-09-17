package com.ecommerce.controller;

import com.ecommerce.common.constant.AuthConstant;
import com.ecommerce.common.dto.LoginDTO;
import com.ecommerce.common.feign.TokenValidateDTO;
import com.ecommerce.common.feign.TokenValidateResult;
import com.ecommerce.common.result.Result;
import com.ecommerce.common.vo.LoginVO;
import com.ecommerce.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：登录 / 退出登录 / token 校验（供网关调用）
 */
@Tag(name = "认证管理")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "登录")
    @PostMapping("/login")
    public Result<LoginVO> login(@Validated @RequestBody LoginDTO loginDTO) {
        return Result.success(userService.login(loginDTO));
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = AuthConstant.HEADER_AUTHORIZATION, required = false) String authHeader) {
        if (authHeader != null && authHeader.startsWith(AuthConstant.BEARER_PREFIX)) {
            userService.logout(authHeader.substring(AuthConstant.BEARER_PREFIX.length()));
        }
        return Result.success();
    }

    @Operation(summary = "校验 token 有效性（网关调用）")
    @PostMapping("/validate")
    public Result<TokenValidateResult> validate(@RequestBody TokenValidateDTO dto) {
        return Result.success(userService.validateToken(dto.getToken()));
    }
}
