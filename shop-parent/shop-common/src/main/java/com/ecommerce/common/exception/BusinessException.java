package com.ecommerce.common.exception;

import com.ecommerce.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常：业务校验不通过时抛出，由全局异常处理器统一捕获
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 业务状态码 */
    private final int code;

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
