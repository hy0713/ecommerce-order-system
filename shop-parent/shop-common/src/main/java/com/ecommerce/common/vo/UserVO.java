package com.ecommerce.common.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户信息视图
 */
@Data
public class UserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String username;

    private String phone;

    private String avatar;

    /** 状态：0 禁用 1 正常 */
    private Integer status;

    /** 角色：ADMIN 管理员 / USER 普通用户 */
    private String role;

    private LocalDateTime createTime;
}
