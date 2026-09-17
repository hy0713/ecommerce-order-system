package com.ecommerce.common.result;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一返回状态码
 * 200 为成功，其余为各类错误码
 */
@Getter
@AllArgsConstructor
public enum ResultCode {

    /** 成功 */
    SUCCESS(200, "操作成功"),

    /** 参数错误 */
    PARAM_ERROR(400, "参数错误"),
    /** 未登录或登录已过期 */
    UNAUTHORIZED(401, "未登录或登录已过期"),
    /** 无权限 */
    FORBIDDEN(403, "无权限"),
    /** 资源不存在 */
    NOT_FOUND(404, "资源不存在"),
    /** 系统异常 */
    ERROR(500, "系统繁忙，请稍后重试"),

    /* ---------- 用户模块 2xxx ---------- */
    LOGIN_FAILED(2001, "用户名或密码错误"),
    USER_DISABLED(2002, "账号已被禁用"),
    USERNAME_EXISTS(2003, "用户名已存在"),

    /* ---------- 商品模块 3xxx ---------- */
    PRODUCT_NOT_FOUND(3001, "商品不存在"),
    PRODUCT_OFF_SHELF(3002, "商品已下架"),
    STOCK_NOT_ENOUGH(3003, "库存不足"),
    CATEGORY_NOT_FOUND(3004, "商品分类不存在"),
    STOCK_CONFLICT(3005, "库存扣减冲突，请重试"),

    /* ---------- 购物车模块 4xxx ---------- */
    CART_EMPTY(4001, "购物车中没有选中的商品"),
    CART_ITEM_NOT_FOUND(4002, "购物车条目不存在"),
    DATA_CONFLICT(4009, "数据已存在，请勿重复提交"),

    /* ---------- 地址模块 5xxx ---------- */
    ADDRESS_NOT_FOUND(5001, "收货地址不存在"),

    /* ---------- 订单模块 6xxx ---------- */
    ORDER_NOT_FOUND(6001, "订单不存在"),
    ORDER_STATUS_ERROR(6002, "当前订单状态不允许该操作"),
    ORDER_CONCURRENT_MODIFY(6003, "订单状态已被其他操作变更，请刷新后重试");

    /** 状态码 */
    private final int code;
    /** 提示信息 */
    private final String message;
}
