package com.ecommerce.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 商品状态枚举
 */
@Getter
@AllArgsConstructor
public enum ProductStatusEnum {

    /** 下架 */
    OFF_SHELF(0, "下架"),
    /** 上架 */
    ON_SHELF(1, "上架");

    private final int code;
    private final String desc;

    public static boolean isOnShelf(Integer status) {
        return ON_SHELF.getCode() == (status == null ? -1 : status);
    }
}
