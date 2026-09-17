# 轻量电商订单系统（阶段一：Spring Boot 单体后端）

商品 → 购物车 → 订单 → 库存 核心业务闭环，基于《轻量电商订单全栈系统 完整实现指南》阶段一规范实现。
后续阶段二（Spring Cloud Alibaba 微服务）、阶段三（Vue3 管理后台）在此代码库上渐进演进。

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
docker compose up -d

# 2. 启动后端（Windows 下需强制 IPv4，见下方注意事项）
MAVEN_OPTS="-Djava.net.preferIPv4Stack=true" ./mvnw spring-boot:run
# 或（JWT 密钥无默认值，必须显式提供，否则 fail-fast）
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

## 安全说明（部署前必读）

| 项 | 说明 |
|---|---|
| 匿名白名单 | 见 `AuthConstant.PUBLIC_ENDPOINTS`。**禁止使用 `/api/xxx/**` 这类宽通配**——曾因此把商品改价/改库存/删除与库存扣减接口全部放行。有单测 `AuthConstantTest` 兜底防回归 |
| 密钥 | JWT / 内部令牌 / MySQL 全部通过环境变量注入（见下表）。**仓库不提供可用默认值**——JWT 缺失即启动失败，内部令牌在 `prod` 下缺失即启动失败；本地由 `start-all.sh` 注入带 `change-me` 标记的开发值 |
| 跨域 | 仅允许 `CORS_ALLOWED_ORIGINS` 中列出的来源，且不启用凭据模式（前端用 Bearer Token） |
| 接口文档 | `/doc.html` 等允许匿名访问，生产请通过网关或反向代理限制来源 |

### 环境变量

| 变量 | 默认值（仅开发） | 用途 |
|---|---|---|
| `JWT_SECRET` | **无（缺失即启动失败）** | JWT HS256 密钥，≥32 字节；本地由 `start-all.sh` 注入开发值 |
| `INTERNAL_TOKEN` | **无（缺失即启动失败）** | 服务间调用令牌 `X-Internal-Token`，阻断绕过网关的直连请求；本地由 `start-all.sh` 注入开发值 |
| `MYSQL_HOST/PORT/DB/USER/PASSWORD` | `localhost/3306/ecommerce/root/root123456` | 数据库连接 |
| `REDIS_HOST/PORT` | `localhost/6379` | Redis 连接 |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,...` | 允许的前端来源（逗号分隔） |

## 目录结构

```
├── docker-compose.yml          # MySQL8 + Redis6.2
├── sql/                        # schema.sql(7表+索引) / data.sql(种子)
├── scripts/                    # smoke-test.sh 全链路 / concurrency-test.sh 并发
├── src/main/java/com/ecommerce/
│   ├── common/                 # result / exception / enums / config / constant / util / interceptor
│   ├── controller/ service/ mapper/ entity/ dto/ vo/
└── pom.xml + mvnw              # Maven Wrapper 自包含构建
```

## 测试与验收结果

```bash
# 单元测试（无需外部依赖，CI 可直接跑）
MAVEN_OPTS="-Djava.net.preferIPv4Stack=true" ./mvnw test

# 集成验收（需要 MySQL + Redis 已启动）
bash scripts/smoke-test.sh      # 全链路：注册→登录→地址→加购→下单→支付→发货→完成→取消→异常场景
bash scripts/concurrency-test.sh # 20 并发 × 库存 5 → 恰好 5 单成功、库存 0、无超卖
```

| 验收标准 | 结果 |
|---|---|
| 白名单匹配规则单测 | ✅ `AuthConstantTest` 9/9 通过（含越权接口防回归） |
| Knife4j 在线调试全部核心接口 | ✅ 27 个接口 |
| 完整下单流程跑通 | ✅ 冒烟测试全部通过（含新增的权限与越权防回归断言） |
| 并发无超卖 | ✅ 20 并发×库存5 → 成功 5 单、库存 0 |
| 并发双取消不重复回补 | ✅ 10 并发取消 → 仅 1 次生效、库存恰好回补 1 次 |
| 异常事务回滚无脏数据 | ✅ 库存扣减回滚、无残留订单 |

> 以上为 2026-09-11 在真实中间件环境（MySQL/Redis/RabbitMQ/Nacos 全部启动）重跑的结果，全绿。
> 管理动作（发货/完成）需用 `admin` 账号登录，脚本已相应调整。
> 详细验收记录见 `核查报告_可执行性与鲁棒性.md` 附录 G。

## 注意事项（本机环境）

1. **Maven 网络**：本机 Java 解析 Maven Central 偶发失败，需 `MAVEN_OPTS="-Djava.net.preferIPv4Stack=true"`
2. **Maven Wrapper**：Git Bash 下 `./mvnw` 可能报 `找不到主类 org.codehaus.plexus.classworlds.launcher.Launcher`（wrapper 已下载完整，是 only-script 模式的路径问题）。绕法：直连 java 调启动器
   ```bash
   D=~/.m2/wrapper/dists/apache-maven-3.9.9/*/; java -classpath "$D/boot/plexus-classworlds-2.8.0.jar" \
     "-Dclassworlds.conf=$D/bin/m2.conf" "-Dmaven.home=$D" \
     -Dmaven.multiModuleProjectDirectory="$PWD" \
     org.codehaus.plexus.classworlds.launcher.Launcher -o -B package
   ```
3. **Docker 镜像**：本机直连 Docker Hub 不通，可配置镜像 `docker.1panel.live`（`docker pull docker.1panel.live/library/xxx && docker tag`）
4. **Git Bash 传参**：向 Windows 原生程序传中文参数会被转成 GBK，curl 请求体请写入文件后 `--data @file`（scripts 中已处理）
5. **curl 走代理**：本机 curl 访问 localhost 可能被代理拦截返回 502，请加 `--noproxy '*'`
6. **日志编码**：应用日志按平台编码（GBK）落盘，用 `grep` 搜中文会搜不到，需先 `iconv -f GBK -t UTF-8`
7. **种子数据编码**：`sql/data.sql` 首行 `SET NAMES utf8mb4` 不可删除，否则容器 initdb 以 latin1 导入产生中文乱码
8. **数据库未就绪**：应用启动时若 MySQL 不可达，`DataInitializer` 会重试约 10 秒后**继续启动**（不再让进程退出），但此时依赖数据库的接口会返回 500，请确认数据库就绪后再验证
