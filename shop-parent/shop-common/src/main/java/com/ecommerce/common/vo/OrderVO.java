package com.ecommerce.common.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单视图
 */
@Data
public class OrderVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String orderNo;

    private BigDecimal totalAmount;

    private Integer orderStatus;

    /** 订单状态中文描述 */
    private String orderStatusDesc;

    private String receiverName;

    private String receiverPhone;

    private String receiverAddress;

    private LocalDateTime payTime;

    private LocalDateTime createTime;

    /** 订单明细 */
    private List<OrderItemVO> items;
}
