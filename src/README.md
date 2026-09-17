# 阶段一：Spring Boot 单体后端

商品 → 购物车 → 订单 → 库存 核心业务闭环的单体实现，是整个项目的起点。
阶段二（Spring Cloud Alibaba 微服务）、阶段三（Vue3 管理后台 + uni-app 小程序）、
AI 智能客服均在此基础上渐进演进，整体结构见[根目录 README](../README.md)。

## 技术栈（版本严格锁定，禁止随意升级）

| 组件 | 版本 | 说明 |
|---|---|---|
| Spring Boot | 2.7.18 | 脚手架 |
| MyBatis-Plus | 3.5.3.1 | ORM、分页、雪花主键、自动填充 |
| MySQL / Redis | 8.0.x / 6.2.x | Docker 容器 |
| Knife4j | 4.3.0 | 接口文档 /doc.html |
| Hutool / Lombok | 5.8.20 / 1.18.30 | 工具集 / 样板代码消除 |
| spring-security-crypto | 5.7.x | BCrypt 密码加密 |
| jjwt | 0.11.5 | JWT 签发与校验 |

## 快速启动

```bash
# 1. 启动 MySQL(3306) + Redis(6379)，首次自动建库建表导入种子数据
#    注意：必须在 shop-parent/ 目录下执行，根目录的 compose 文件是本阶段的精简版
docker compose up -d

# 2. 启动后端（Windows 下需强制 IPv4，见根目录 README 注意事项）
MAVEN_OPTS="-Djava.net.preferIPv4Stack=true" ./mvnw spring-boot:run
# 或（JWT 密钥无默认值，必须显式提供，否则 fail-fast 拒绝启动）
# JWT_SECRET='<>=32字节的密钥>' java -jar target/helpbydsv4.jar

# 3. 接口文档（Knife4j，在线调试）
http://localhost:8080/doc.html
```

> **已有数据卷时的升级**：`sql/schema.sql` 新增了 `user.role` 列与 `stock_compensation` 表。
> 若沿用旧的数据卷（initdb 不会重跑），请执行一次增量脚本：
> ```bash
> docker exec -i ecommerce-mysql mysql -uroot -proot123456 < sql/migration_2026_09.sql
> # 或直接重建：docker compose down -v && docker compose up -d
> ```

- 默认账号：`admin / 123456`（启动时自动创建，BCrypt 加密，**角色 ADMIN**）；自助注册的账号为普通用户 USER
- 种子数据：12 个分类（两级树）、11 个商品（含低库存与已下架商品，供测试）

## 核心设计

- **统一返回**：`Result<T>`（code/message/data），code=200 成功；全局异常处理三类异常
- **认证**：登录签发 JWT（含角色声明 ADMIN/USER）并写入 Redis（24h），`JwtAuthInterceptor` 验签 + Redis 双校验
- **授权（重要）**：匿名白名单按「HTTP 方法 + 路径」**精确匹配**（`AuthConstant.PUBLIC_ENDPOINTS`），只放行只读接口与登录注册；商品/分类的写接口、订单管理接口均需登录，其中发货/完成/统计等管理动作要求 **ADMIN 角色**（`UserContext.requireAdmin()`，否则 403）
- **商品缓存**：详情缓存 30 分钟 + 空值缓存 5 分钟防穿透；上下架/修改同步清缓存
- **下单事务**（`@Transactional`）：地址校验 → 选中商品校验 → 行锁内扣库存 → 金额计算 → 雪花订单号 → 快照落库 → 清购物车；任一环节失败整体回滚
- **防超卖**：`SELECT ... FOR UPDATE` 排他行锁（当前读，绕过 REPEATABLE READ 快照）把「判断库存 → 扣减」串行化，是并发正确性的主要保障；UPDATE 语句再带 `stock >= ? AND version = ?` 作为数据库层兜底。**行锁有效时条件更新不会因版本冲突失败，因此不再保留重试循环**（历史实现的「乐观锁重试最多 5 次」在行锁保护下永远不可达，属死代码）
- **订单状态流转**：一律使用条件更新 `UPDATE ... WHERE id=? AND order_status=?` 并校验受影响行数，由数据库决定唯一赢家。**禁止「先查状态再无条件 updateById」**——并发双取消会重复回补库存
- **取消回补**：仅待支付可取消，置状态与回补在同一事务内，回补失败整体回滚（订单保持待支付可重试）
- **购物车**：同用户同商品数量累加、实时关联商品价格/状态、异常项标记；加购与改数量使用同一套库存校验口径

## 测试与验收

```bash
# 单元测试（无需外部依赖，CI 可直接跑）
MAVEN_OPTS="-Djava.net.preferIPv4Stack=true" ./mvnw test

# 集成验收（需要 MySQL + Redis 已启动）
bash scripts/smoke-test.sh       # 全链路：注册→登录→地址→加购→下单→支付→发货→完成→取消→异常场景
bash scripts/concurrency-test.sh # 20 并发 × 库存 5 → 恰好 5 单成功、库存 0、无超卖
```

| 验收标准 | 结果 |
|---|---|
| 白名单匹配规则单测 | ✅ `AuthConstantTest` 全绿（含越权接口防回归） |
| Knife4j 在线调试全部核心接口 | ✅ 27 个接口 |
| 完整下单流程跑通 | ✅ 冒烟测试全部通过（含权限与越权防回归断言） |
| 并发无超卖 | ✅ 20 并发×库存5 → 成功 5 单、库存 0 |
| 并发双取消不重复回补 | ✅ 10 并发取消 → 仅 1 次生效、库存恰好回补 1 次 |
| 异常事务回滚无脏数据 | ✅ 库存扣减回滚、无残留订单 |

## 目录结构

```
├── sql/                        # schema.sql(7表+索引) / data.sql(种子) / migration_2026_09.sql
├── scripts/                    # smoke-test.sh 全链路 / concurrency-test.sh 并发
├── src/main/java/com/ecommerce/
│   ├── common/                 # result / exception / enums / config / constant / util / interceptor
│   ├── controller/ service/ mapper/ entity/ dto/ vo/
└── pom.xml + mvnw              # Maven Wrapper 自包含构建
```
