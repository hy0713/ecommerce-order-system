package com.ecommerce.common.feign;

import lombok.Data;

import java.io.Serializable;

/**
 * token 校验结果（用户服务返回给网关）
 */
@Data
public class TokenValidateResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否有效 */
    private boolean valid;

    /** 有效时的用户ID */
    private Long userId;

    /** 有效时的用户角色（ADMIN / USER） */
    private String role;
}
