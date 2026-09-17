# 轻量电商订单系统（阶段二：Spring Cloud Alibaba 微服务版）

在阶段一单体基础上按规格 3.x 拆分为 4 服务微服务架构，位于 `shop-parent/` 多模块工程。根目录单体保留可独立运行。

## 技术栈（版本严格锁定）

| 组件 | 版本 | 说明 |
|---|---|---|
| Spring Boot / Spring Cloud | 2.7.18 / 2021.0.8 | Gateway/OpenFeign/LoadBalancer 3.1.8 |
| Spring Cloud Alibaba | 2021.0.5.0 | Nacos Discovery（客户端 2.2.1）、Sentinel 1.8.6 |
| Nacos Server | 2.2.3 | 注册中心（本地 zip standalone，见下方启动） |
| RabbitMQ | 3.x | 延迟消息用「TTL + 死信队列」实现（无需插件） |
| Redisson | 3.20.0 | 库存扣减分布式锁（原生依赖，不接管 Spring Data Redis） |
| MyBatis-Plus / MySQL / Redis / Knife4j | 同阶段一 | 4 服务共用 MySQL 实例，各操作自己的表 |

## 服务与端口

| 服务 | 端口 | 职责 | 数据表 |
|---|---|---|---|
| shop-gateway | 8080 | 路由/鉴权/跨域/日志/Sentinel 限流 | - |
| shop-user | 8081 | 登录注册、token 校验、地址管理 | user, user_address |
| shop-product | 8082 | 商品/分类、库存扣减回补（Redisson 锁+乐观锁） | product_category, product_info |
| shop-order | 8083 | 购物车、订单、超时自动取消（Feign 调用商品/用户服务） | shopping_cart, order_master, order_detail |

## 启动步骤

```bash
# 1. 中间件（MySQL/Redis/RabbitMQ）
docker compose up -d mysql redis rabbitmq

# 2. Nacos 2.2.3（standalone，内嵌 Derby）
#    发行包：e:\cscode\java\tools\nacos-2.2.3（GitHub 下载，多线程分段脚本见 tools/fast-download.py）
java -Duser.timezone=Asia/Shanghai -Dnacos.standalone=true -Xms512m -Xmx512m -jar target/nacos-server.jar

# 3. 构建并启动 4 个服务（Windows 需 MAVEN_OPTS="-Djava.net.preferIPv4Stack=true"）
mvn clean install -DskipTests
java -Duser.timezone=Asia/Shanghai -jar shop-user/target/shop-user-1.0.0.jar      # 8081
java -Duser.timezone=Asia/Shanghai -jar shop-product/target/shop-product-1.0.0.jar # 8082
java -Duser.timezone=Asia/Shanghai -jar shop-order/target/shop-order-1.0.0.jar     # 8083
java -Duser.timezone=Asia/Shanghai -jar shop-gateway/target/shop-gateway-1.0.0.jar # 8080

# 4. 验证：http://localhost:8080（网关）/ 8848（Nacos 控制台 nacos/nacos）
#    Knife4j：8081/8082/8083/doc.html
```

## 核心设计

- **网关鉴权**：GlobalFilter 调用户服务 `/api/auth/validate`（验签+Redis）校验 token，通过后透传 `X-User-Id` 与 `X-User-Role`；服务间 Feign 调用自动透传用户上下文（UserIdFeignRequestInterceptor）
- **接口边界（重要）**
  - **匿名白名单按「HTTP 方法 + 路径」精确匹配**（`AuthConstant.PUBLIC_ENDPOINTS`），只放行登录注册与商品/分类只读接口。**禁止使用 `/api/xxx/**` 宽通配**——曾把商品改价/改库存/删除与库存扣减接口一并放行
  - **内部接口一律置于 `/api/internal/` 前缀**（如库存扣减/回补），网关对该前缀直接返回 404 且不配置路由
  - **阻断绕过网关**：网关会剥离客户端伪造的 `X-User-Id`/`X-User-Role`/`X-Internal-Token` 后再写入认定值；业务服务侧校验 `X-Internal-Token`，缺失即 403，因此直连 8081/8082/8083 无法冒充身份
  - **角色控制**：JWT 携带角色声明，发货/完成/管理取消/经营统计/商品与分类写接口要求 ADMIN（`UserContext.requireAdmin()`，否则 403）
- **分布式库存**：商品服务内「Redisson 锁（30s 租约）+ `SELECT ... FOR UPDATE` 行锁当前读」串行化扣减，UPDATE 再带 `stock >= ? AND version = ?` 兜底。**扣减/回补方法必须带 `@Transactional`**——否则 autocommit 下行锁语句结束即释放，「当前读」形同虚设。行锁有效时条件更新不会冲突，故不保留重试循环
- **订单状态流转**：一律条件更新 `UPDATE ... WHERE id=? AND order_status=?` 并校验受影响行数，由数据库决定唯一赢家。**禁止「先查状态再无条件 updateById」**——并发的「用户取消」与「超时取消」会同时通过校验导致库存被回补两次
- **跨服务下单**：无强一致事务，采用失败补偿 —— 任一商品扣减失败或本地建单失败，已扣商品逐个回补。**回补失败不再只打日志**，而是写入 `stock_compensation` 表（唯一键 `(order_no, product_id, biz_type)` 保证幂等），由定时任务持续重试
- **超时自动取消**：下单后发 RabbitMQ 消息进 TTL 队列（30 分钟），到期经死信队列消费取消 + Feign 回补；幂等由**状态条件更新**保证（不再依赖 Redis SETNX —— 历史实现把幂等键写在事务外，回滚后键仍存活会挡住兜底重试）；`@Scheduled` 30s 扫描兜底（分批 200 条）
- **消息不丢**：消费者队列配置死信交换机，配合 `retry.enabled=true` + `default-requeue-rejected=false`，重试耗尽后归档到 `order.timeout.failed.queue` 供人工排查
- **限流**：Sentinel 网关 API 分组（order-create = POST /api/order），单机 20 QPS + 单 IP 5 QPS，BlockExceptionFilter 统一返回 429
- **业务校验实时化**：加购/下单用 `/api/product/{id}/fresh` 直查 DB（详情页才走 Redis 缓存），避免缓存脏读导致误判

## 安全与配置（部署前必读）

| 项 | 说明 |
|---|---|
| 内部令牌 | `ecommerce.internal.token`（环境变量 `INTERNAL_TOKEN`），网关与 4 个业务服务必须一致；**缺失即启动失败**，不留"跳过校验"的降级路径 |
| 密钥 | `JWT_SECRET` / `MYSQL_*` / `NACOS_*` / `RABBITMQ_*` 全部支持环境变量注入，仓库内默认值仅供本地开发 |
| 跨域 | `CORS_ALLOWED_ORIGINS` 显式列举，`allowCredentials: false`（前端用 Bearer Token） |
| Feign 超时 | 前缀是 `feign.client.config.*`（**不是** `spring.cloud.openfeign.*`，后者是 2022.0+ 才引入的，写在 2021.0.x 上会被静默忽略） |
| 刻意不配 Feign 重试 | 库存扣减/回补非幂等，网络超时重试会造成重复扣减/回补；最终一致由补偿表保证 |

## 验收结果（全部通过）

| 验收标准（规格 3.6） | 结果 |
|---|---|
| 4 服务注册 Nacos 状态正常 | ✅ shop-user/product/order/gateway 全部注册 |
| 经网关访问所有接口链路通畅 | ✅ 冒烟测试 29/29（`bash scripts/smoke-test-ms.sh`） |
| 分布式下单高并发无超卖无脏数据 | ✅ 20 并发×库存5 → 5 单成功、库存 0（`bash scripts/concurrency-test-ms.sh`） |
| 订单超时自动取消、库存正确回补 | ✅ TTL 15s 验证：取消+回补；定时兜底：31 分钟脏数据自动取消且不重复回补 |
| 网关限流返回统一错误码 | ✅ 12 并发下单 → 5 通过 7 个 429 |

## 本机注意事项

1. **Nacos 不跑 Docker**（镜像源受限）：用本地 zip + `java -jar`；多线程分段下载脚本 `tools/fast-download.py`
2. **RabbitMQ 延迟**：本机镜像不含 delayed_message_exchange 插件，故用 TTL+死信实现（规格"RabbitMQ 延迟队列"的等价实现）。
   队列声明参数变更后**必须删队列重建**，否则启动报 `PRECONDITION_FAILED`：
   ```bash
   rabbitmqctl delete_queue order.wait.queue
   rabbitmqctl delete_queue order.delay.queue
   rabbitmqctl delete_queue order.timeout.failed.queue
   ```
3. **时区**：所有服务统一 `-Duser.timezone=Asia/Shanghai`（JVM 默认时区曾导致调度阈值错 6 小时）
4. **数据库升级**：`sql/schema.sql` 新增 `user.role` 与 `stock_compensation` 表；沿用旧数据卷时执行
   `docker exec -i ecommerce-mysql mysql -uroot -proot123456 < sql/migration_2026_09.sql`
5. **服务间令牌**：`INTERNAL_TOKEN` 必须在网关与 4 个业务服务间保持一致，否则业务服务会因缺少内部令牌返回 403。
   该值**没有默认值**，缺失时网关与业务服务都会拒绝启动（`InternalTokenGuard`）；本地由 `start-all.sh` 注入，单独起单个服务需自行 `export INTERNAL_TOKEN=...`
6. 其他环境坑同根目录 README（Maven IPv4 / mvnw 直连启动器 / Git Bash GBK 传参 / curl `--noproxy '*'` / 日志 GBK 编码 / initdb 乱码）
