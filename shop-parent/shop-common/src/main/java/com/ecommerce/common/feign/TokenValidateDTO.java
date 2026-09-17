package com.ecommerce.common.feign;

import lombok.Data;

import java.io.Serializable;

/**
 * token 校验入参（网关 → 用户服务）
 */
@Data
public class TokenValidateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** JWT token（不含 Bearer 前缀） */
    private String token;
}
