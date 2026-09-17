package com.ecommerce.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

/**
 * 下单入参
 */
@Data
public class OrderCreateDTO {

    /** 收货地址ID */
    @NotNull(message = "收货地址不能为空")
    private Long addressId;
}
