-- =============================================================
-- 轻量电商订单系统 阶段一 数据库脚本
-- 数据库：ecommerce（MySQL 8.0）
-- 主键：bigint 雪花算法；时间字段：datetime 自动填充
-- =============================================================
CREATE DATABASE IF NOT EXISTS `ecommerce`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE `ecommerce`;

-- -------------------------------------------------------------
-- 1. 用户表
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user` (
  `id`          bigint       NOT NULL COMMENT '用户ID',
  `username`    varchar(32)  NOT NULL COMMENT '用户名',
  `password`    varchar(128) NOT NULL COMMENT 'BCrypt加密密码',
  `phone`       varchar(16)           DEFAULT NULL COMMENT '手机号',
  `avatar`      varchar(255)          DEFAULT NULL COMMENT '头像地址',
  `status`      tinyint      NOT NULL DEFAULT 1 COMMENT '状态：0 禁用 1 正常',
  `role`        varchar(16)  NOT NULL DEFAULT 'USER' COMMENT '角色：ADMIN 管理员 USER 普通用户',
  `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_username` (`username`)
) ENGINE = InnoDB COMMENT = '用户表';

-- -------------------------------------------------------------
-- 2. 商品分类表
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `product_category` (
  `id`          bigint      NOT NULL COMMENT '分类ID',
  `name`        varchar(64) NOT NULL COMMENT '分类名称',
  `parent_id`   bigint      NOT NULL DEFAULT 0 COMMENT '父分类ID，0为一级分类',
  `sort`        int         NOT NULL DEFAULT 0 COMMENT '排序权重，数值越小越靠前',
  `status`      tinyint     NOT NULL DEFAULT 1 COMMENT '状态：0 禁用 1 启用',
  `create_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_parent_id` (`parent_id`)
) ENGINE = InnoDB COMMENT = '商品分类表';

-- -------------------------------------------------------------
-- 3. 商品表
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `product_info` (
  `id`          bigint         NOT NULL COMMENT '商品ID',
  `category_id` bigint         NOT NULL COMMENT '所属分类ID',
  `name`        varchar(128)   NOT NULL COMMENT '商品名称',
  `price`       decimal(10, 2) NOT NULL COMMENT '商品单价',
  `stock`       int            NOT NULL DEFAULT 0 COMMENT '库存数量',
  `description` text                   DEFAULT NULL COMMENT '商品描述',
  `icon`        varchar(255)           DEFAULT NULL COMMENT '商品主图地址',
  `status`      tinyint        NOT NULL DEFAULT 1 COMMENT '状态：0 下架 1 上架',
  `version`     int            NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `create_time` datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_category_id` (`category_id`),
  KEY `idx_status` (`status`)
) ENGINE = InnoDB COMMENT = '商品表';

-- -------------------------------------------------------------
-- 4. 购物车表
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `shopping_cart` (
  `id`          bigint   NOT NULL COMMENT '主键',
  `user_id`     bigint   NOT NULL COMMENT '用户ID',
  `product_id`  bigint   NOT NULL COMMENT '商品ID',
  `quantity`    int      NOT NULL DEFAULT 1 COMMENT '商品数量',
  `selected`    tinyint  NOT NULL DEFAULT 1 COMMENT '是否选中：0 未选 1 选中',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_user_product` (`user_id`, `product_id`)
) ENGINE = InnoDB COMMENT = '购物车表';

-- -------------------------------------------------------------
-- 5. 用户地址表
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_address` (
  `id`             bigint       NOT NULL COMMENT '主键',
  `user_id`        bigint       NOT NULL COMMENT '用户ID',
  `receiver_name`  varchar(32)  NOT NULL COMMENT '收货人姓名',
  `receiver_phone` varchar(16)  NOT NULL COMMENT '收货人电话',
  `address`        varchar(255) NOT NULL COMMENT '详细收货地址',
  `is_default`     tinyint      NOT NULL DEFAULT 0 COMMENT '是否默认：0 否 1 是',
  `create_time`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB COMMENT = '用户地址表';

-- -------------------------------------------------------------
-- 6. 订单主表
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_master` (
  `id`               bigint         NOT NULL COMMENT '主键',
  `order_no`         varchar(32)    NOT NULL COMMENT '订单编号（雪花算法生成）',
  `user_id`          bigint         NOT NULL COMMENT '下单用户ID',
  `total_amount`     decimal(10, 2) NOT NULL COMMENT '订单总金额',
  `order_status`     tinyint        NOT NULL DEFAULT 0 COMMENT '订单状态：0 待支付 1 已支付 2 已发货 3 已完成 4 已取消',
  `receiver_name`    varchar(32)    NOT NULL COMMENT '收货人姓名',
  `receiver_phone`   varchar(16)    NOT NULL COMMENT '收货人电话',
  `receiver_address` varchar(255)   NOT NULL COMMENT '收货地址',
  `pay_time`         datetime                DEFAULT NULL COMMENT '支付时间',
  `create_time`      datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`      datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_order_no` (`order_no`),
  KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB COMMENT = '订单主表';

-- -------------------------------------------------------------
-- 7. 订单明细表
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_detail` (
  `id`               bigint         NOT NULL COMMENT '主键',
  `order_no`         varchar(32)    NOT NULL COMMENT '关联订单主表订单号',
  `product_id`       bigint         NOT NULL COMMENT '商品ID',
  `product_name`     varchar(128)   NOT NULL COMMENT '商品名称快照',
  `product_price`    decimal(10, 2) NOT NULL COMMENT '商品单价快照',
  `product_quantity` int            NOT NULL COMMENT '购买数量',
  `create_time`      datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_order_no` (`order_no`)
) ENGINE = InnoDB COMMENT = '订单明细表';

-- -------------------------------------------------------------
-- 8. 库存补偿流水表（阶段二微服务：跨服务回补失败后的最终一致保证）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `stock_compensation` (
  `id`          bigint       NOT NULL COMMENT '主键',
  `order_no`    varchar(32)  NOT NULL COMMENT '关联订单号',
  `product_id`  bigint       NOT NULL COMMENT '需回补的商品ID',
  `quantity`    int          NOT NULL COMMENT '需回补数量',
  `biz_type`    varchar(24)  NOT NULL COMMENT '业务类型：ORDER_CANCEL 取消回补 / CREATE_FAIL 下单失败补偿',
  `status`      tinyint      NOT NULL DEFAULT 0 COMMENT '状态：0 待处理 1 已成功',
  `retry_count` int          NOT NULL DEFAULT 0 COMMENT '已重试次数',
  `last_error`  varchar(255)          DEFAULT NULL COMMENT '最近一次失败原因',
  `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_order_product_type` (`order_no`, `product_id`, `biz_type`),
  KEY `idx_compensation_status` (`status`)
) ENGINE = InnoDB COMMENT = '库存补偿流水表';
