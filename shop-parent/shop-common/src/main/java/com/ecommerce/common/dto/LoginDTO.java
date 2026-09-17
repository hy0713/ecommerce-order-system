package com.ecommerce.common.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 登录入参
 */
@Data
public class LoginDTO {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}
