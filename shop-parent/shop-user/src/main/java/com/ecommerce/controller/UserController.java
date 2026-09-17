package com.ecommerce.controller;

import com.ecommerce.common.dto.RegisterDTO;
import com.ecommerce.common.result.Result;
import com.ecommerce.common.util.UserContext;
import com.ecommerce.common.vo.UserVO;
import com.ecommerce.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口
 */
@Tag(name = "用户管理")
@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "注册")
    @PostMapping("/register")
    public Result<Void> register(@Validated @RequestBody RegisterDTO registerDTO) {
        userService.register(registerDTO);
        return Result.success();
    }

    @Operation(summary = "查询当前登录用户信息")
    @GetMapping("/info")
    public Result<UserVO> getUserInfo() {
        return Result.success(userService.getUserInfo(UserContext.getUserId()));
    }
}
