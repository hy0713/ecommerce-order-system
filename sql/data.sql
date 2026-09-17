-- =============================================================
-- 种子数据：分类 + 示例商品（admin 账号由 Java 启动器创建）
-- 注意：必须 SET NAMES utf8mb4，否则容器 initdb 以 latin1 导入会中文乱码
-- =============================================================
USE `ecommerce`;
SET NAMES utf8mb4;

-- 商品分类（一级分类）
INSERT INTO `product_category` (`id`, `name`, `parent_id`, `sort`, `status`) VALUES
  (1, '手机数码', 0, 1, 1),
  (2, '电脑办公', 0, 2, 1),
  (3, '家用电器', 0, 3, 1),
  (4, '服饰鞋包', 0, 4, 1);

-- 商品分类（二级分类，演示树形）
INSERT INTO `product_category` (`id`, `name`, `parent_id`, `sort`, `status`) VALUES
  (11, '手机',   1, 1, 1),
  (12, '耳机',   1, 2, 1),
  (21, '笔记本电脑', 2, 1, 1),
  (22, '机械键盘',   2, 2, 1),
  (31, '空调',   3, 1, 1),
  (32, '冰箱',   3, 2, 1),
  (41, '男装',   4, 1, 1),
  (42, '女装',   4, 2, 1);

-- 示例商品（id=10 低库存商品，用于并发下单测试；id=11 下架商品，用于校验异常场景）
INSERT INTO `product_info`
  (`id`, `category_id`, `name`, `price`, `stock`, `description`, `icon`, `status`, `version`) VALUES
  (1,  11, 'iPhone 15 Pro',      8999.00, 100, '苹果旗舰手机 256G 原色钛金属', 'https://picsum.photos/seed/p1/400/400', 1, 0),
  (2,  11, '华为 Mate 60 Pro',    6999.00, 100, '华为旗舰手机 12G+512G 雅丹黑',  'https://picsum.photos/seed/p2/400/400', 1, 0),
  (3,  12, 'AirPods Pro 2',      1899.00, 150, '主动降噪无线耳机',              'https://picsum.photos/seed/p3/400/400', 1, 0),
  (4,  21, 'MacBook Pro 14',    14999.00, 50,  '苹果笔记本电脑 M3 Pro 18G+512G',  'https://picsum.photos/seed/p4/400/400', 1, 0),
  (5,  21, '联想小新 Pro 16',    5499.00, 80,  '轻薄办公本 R7-7840H 16G+1T',      'https://picsum.photos/seed/p5/400/400', 1, 0),
  (6,  22, '罗技 G502',           299.00, 200, '游戏鼠标 HERO 25K',               'https://picsum.photos/seed/p6/400/400', 1, 0),
  (7,  22, '樱桃 MX3.0S',         499.00, 5,   '机械键盘 87键 茶轴',               'https://picsum.photos/seed/p7/400/400', 1, 0),
  (8,  31, '格力云佳空调',       2699.00, 60,  '1.5匹 变频 新一级能效',            'https://picsum.photos/seed/p8/400/400', 1, 0),
  (9,  32, '海尔 501L 冰箱',     3299.00, 40,  '十字对开门 一级能效 风冷无霜',     'https://picsum.photos/seed/p9/400/400', 1, 0),
  (10, 22, '限量版客制化键盘',   1299.00, 5,   '低库存商品，用于并发下单测试',     'https://picsum.photos/seed/p10/400/400', 1, 0),
  (11, 41, '男士纯棉T恤',         129.00, 99,  '已下架商品，测试加购拦截',         'https://picsum.photos/seed/p11/400/400', 0, 0);
