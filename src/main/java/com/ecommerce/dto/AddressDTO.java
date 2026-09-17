package com.ecommerce.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/**
 * 收货地址入参（新增 / 修改共用）
 */
@Data
public class AddressDTO {

    @NotBlank(message = "收货人姓名不能为空")
    @Size(max = 32, message = "收货人姓名长度不能超过 32 位")
    private String receiverName;

    @NotBlank(message = "收货人电话不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "收货人电话格式不正确")
    private String receiverPhone;

    @NotBlank(message = "收货地址不能为空")
    @Size(max = 255, message = "收货地址长度不能超过 255 位")
    private String address;

    @Min(value = 0, message = "是否默认参数错误")
    @Max(value = 1, message = "是否默认参数错误")
    private Integer isDefault;
}
