package com.ecommerce.common.feign;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 库存扣减结果（商品服务返回给订单服务）
 * 扣减失败不抛异常，通过 success 与 code 返回业务原因，便于下单方补偿
 */
@Data
public class StockDeductResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否扣减成功 */
    private boolean success;

    /** 业务错误码（成功时为 200） */
    private int code;

    /** 提示信息 */
    private String message;

    /** 商品名称（快照用） */
    private String productName;

    /** 商品当前单价（快照与金额计算用） */
    private BigDecimal price;

    public static StockDeductResult ok(String productName, BigDecimal price) {
        StockDeductResult result = new StockDeductResult();
        result.setSuccess(true);
        result.setCode(200);
        result.setProductName(productName);
        result.setPrice(price);
        return result;
    }

    public static StockDeductResult fail(int code, String message) {
        StockDeductResult result = new StockDeductResult();
        result.setSuccess(false);
        result.setCode(code);
        result.setMessage(message);
        return result;
    }
}
