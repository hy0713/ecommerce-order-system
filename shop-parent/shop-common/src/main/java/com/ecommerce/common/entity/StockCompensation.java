package com.ecommerce.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 库存补偿流水：记录「应当回补但调用失败」的库存回补任务，由定时任务重试。
 *
 * <p>跨服务下单/取消没有强一致事务，回补失败如果只打日志就会造成库存永久少卖。
 * 本表把失败任务落库，配合 {@code StockCompensationScheduler} 实现最终一致。
 *
 * <p>唯一键 (order_no, product_id, biz_type) 保证同一笔回补只登记一次，天然幂等。
 */
@Data
@TableName("stock_compensation")
public class StockCompensation implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 补偿任务状态：待处理 */
    public static final int STATUS_PENDING = 0;

    /** 补偿任务状态：已成功 */
    public static final int STATUS_DONE = 1;

    /** 业务类型：订单取消（含超时取消）回补 */
    public static final String BIZ_ORDER_CANCEL = "ORDER_CANCEL";

    /** 业务类型：下单失败补偿回补 */
    public static final String BIZ_CREATE_FAIL = "CREATE_FAIL";

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联订单号 */
    private String orderNo;

    /** 需回补的商品ID */
    private Long productId;

    /** 需回补的数量 */
    private Integer quantity;

    /** 业务类型：ORDER_CANCEL / CREATE_FAIL */
    private String bizType;

    /** 状态：0 待处理 1 已成功 */
    private Integer status;

    /** 已重试次数 */
    private Integer retryCount;

    /** 最近一次失败原因 */
    private String lastError;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
