-- =============================================================
-- 增量迁移脚本（2026-09 安全加固与一致性修复）
-- 适用：已存在数据卷、不会重新执行 schema.sql 的环境
--
-- 用法（必须指定 utf8mb4，否则中文注释会乱码）：
--   docker exec -i ecommerce-mysql mysql --default-character-set=utf8mb4 \
--     -uroot -proot123456 < sql/migration_2026_09.sql
--
-- 说明：脚本按「可重复执行」编写（先判存在再做），重复运行不会报错
-- =============================================================
USE `ecommerce`;
SET NAMES utf8mb4;

-- -------------------------------------------------------------
-- 1. user 表新增 role 列（管理员/普通用户）
--    自助注册一律 USER；admin 账号由应用启动器补全为 ADMIN
-- -------------------------------------------------------------
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'ecommerce' AND TABLE_NAME = 'user' AND COLUMN_NAME = 'role'
);
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `user` ADD COLUMN `role` varchar(16) NOT NULL DEFAULT ''USER'' COMMENT ''角色：ADMIN 管理员 USER 普通用户'' AFTER `status`',
  'SELECT "user.role 已存在，跳过"');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 存量 admin 账号补为管理员，避免升级后无人能发货/完成订单
UPDATE `user` SET `role` = 'ADMIN' WHERE `username` = 'admin' AND (`role` IS NULL OR `role` = '' OR `role` = 'USER');

-- -------------------------------------------------------------
-- 2. stock_compensation 库存补偿流水表
--    跨服务回补失败时落库，由 OrderTimeoutScheduler 定时重试
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
