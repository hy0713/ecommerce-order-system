package com.ecommerce.common.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;

/**
 * 登录结果视图：token + 用户信息
 */
@Data
@AllArgsConstructor
public class LoginVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String token;

    private UserVO userInfo;
}
