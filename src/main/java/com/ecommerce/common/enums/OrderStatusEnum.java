package com.ecommerce.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 订单状态枚举
 */
@Getter
@AllArgsConstructor
public enum OrderStatusEnum {

    /** 待支付 */
    WAIT_PAY(0, "待支付"),
    /** 已支付 */
    PAID(1, "已支付"),
    /** 已发货 */
    SHIPPED(2, "已发货"),
    /** 已完成 */
    FINISHED(3, "已完成"),
    /** 已取消 */
    CANCELED(4, "已取消");

    private final int code;
    private final String desc;

    /**
     * 按状态码查枚举，未匹配返回 null（调用方负责判空）。
     *
     * <p>形参用包装类型 {@code Integer} 而非 {@code int}：状态码来自数据库可空列，
     * 若声明为 int，传 null 时会先在拆箱处抛 NPE，调用方的
     * {@code of(...) == null} 判空分支永远没有机会执行。
     */
    public static OrderStatusEnum of(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(e -> e.getCode() == code)
                .findFirst()
                .orElse(null);
    }

    public static boolean isValid(int code) {
        return of(code) != null;
    }
}
