# 轻量电商订单全栈系统

这是一个面向 2027 届 Java 后端求职展示的可运行电商项目，覆盖商品、购物车、下单、库存、订单状态、超时取消、库存回补以及 AI 客服。

项目重点不是构建生产级电商基础设施，而是把常见业务流程、并发控制、权限边界、消息幂等和异常处理做得清楚、可靠、可以在面试中解释。

GitHub：[hy0713/ecommerce-order-system](https://github.com/hy0713/ecommerce-order-system)

## 项目概览

核心业务链路：

```text
商品浏览 → 加入购物车 → 创建订单 → 扣减库存
                         ↓
              支付/取消/超时取消
                         ↓
                    库存回补
```

当前仓库同时保留了单体版本和微服务版本，便于展示从单体到 Spring Cloud Alibaba 的演进过程：

| 模块 | 作用 |
| --- | --- |
| `shop-parent` | 当前主要运行版本：Gateway、用户、商品、订单和公共模块 |
| `src` | 单体版本/演进对照代码 |
| `shop-web` | Vue 3 管理后台与业务前端 |
| `ecommerce-mini-program` | uni-app 微信小程序端 |
| `ecommerce-agent-python` | FastAPI + RAG + Function Calling AI 客服 |
| `sql` | 数据库初始化脚本和迁移脚本 |

## 系统结构

```text
Vue 3 管理后台 / uni-app 小程序
              │ JWT
              ▼
      shop-gateway :8080
       ├── shop-user    :8081  用户、登录、地址
       ├── shop-product :8082  商品、分类、库存
       └── shop-order   :8083  购物车、订单、超时处理
              │
       Nacos 服务发现与配置

MySQL ─ Redis ─ RabbitMQ(TTL + DLX)

AI 客服：FastAPI :8000
         通过 Gateway 访问业务接口和订单/商品查询能力
```

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 2.7.18、Spring Cloud 2021.0.8、Spring Cloud Alibaba 2021.0.5.0 |
| 微服务 | Spring Cloud Gateway、OpenFeign、LoadBalancer、Nacos、Sentinel |
| 数据访问 | MyBatis-Plus、MySQL 8、Redis 6、Redisson |
| 消息 | RabbitMQ 3.x、TTL、死信交换机、消费幂等、有限重试 |
| 前端 | Vue 3、Vite、Pinia、Element Plus、Axios |
| 小程序 | uni-app、Vue 3、Pinia、uView Plus |
| AI | FastAPI、SQLAlchemy Async、LangChain、DeepSeek OpenAI 兼容接口、fastembed |

## 值得关注的实现

### 库存与并发

- 库存操作使用 Redisson 分布式锁保护同一商品的并发入口。
- 数据库扣减在事务内完成，通过行锁/条件更新和更新结果判断避免常见并发下的负库存与超卖。
- 下单涉及远程库存扣减和本地订单落库时，本地落库失败会记录库存补偿，补偿任务使用独立事务和行级抢占，避免重复回补。
- 这套设计解决的是项目范围内的常见并发与重复请求问题，不宣称金融级 Exactly Once。

### 订单状态与超时取消

- 订单状态转换使用条件更新，避免已支付订单被超时任务错误取消。
- 超时取消采用 RabbitMQ TTL + DLX，并保留定时扫描作为兜底。
- 重复到达的超时消息会先检查订单当前状态；已处理订单不会再次扣减或回补库存。

### 权限与服务边界

- Gateway 校验 JWT，并根据路由和方法做访问控制。
- 用户服务、订单服务等关键查询会校验资源归属，用户不能通过修改订单 ID 查询他人订单。
- 服务间调用使用内部令牌；客户端不能直接伪造可信的内部身份头。

### 缓存与消息

- 商品详情使用 Redis 缓存，商品写操作会处理对应缓存失效。
- 交易库存以数据库结果为准，不把可能过期的展示缓存作为扣库存依据。
- 消费端按业务键做幂等判断，并对失败消息采用有限重试，避免无限重试拖垮队列。

### AI 客服

- 使用关键词/BM25 与向量检索组合，再通过 RRF 融合结果。
- 通过 Function Calling 查询订单状态、商品库存和售后政策。
- 会话上下文放在 Redis，业务数据通过异步 MySQL 访问。
- AI 服务是可选模块，不影响 Java 电商主链路启动。

## 本地运行

### 环境要求

- JDK 17
- Maven 3.9+（仓库提供 Maven Wrapper）
- Node.js 18+
- Docker Desktop 或 Docker Engine
- Nacos Server 2.2.3（当前脚本按本地安装目录启动）
- Python 3.12（仅启用 AI 客服时需要）

### 一键启动演示环境

`start-all.sh` 会启动 MySQL、Redis、RabbitMQ、Nacos，以及已经构建好的 Java 服务；前端和 AI 服务在依赖准备好后自动启动。

```bash
# 1. 构建微服务 JAR
cd shop-parent
mvn clean package -DskipTests
cd ..

# 2. 可选：准备 Vue 前端
cd shop-web
npm install
cd ..

# 3. 可选：准备 AI 客服
cd ecommerce-agent-python
python3.12 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
# 在 .env 中填写 LLM_API_KEY
cd ..

# 4. 启动完整演示环境
bash start-all.sh
```

如果 Nacos 不在脚本默认目录 `/e/cscode/java/tools/nacos-2.2.3`，先设置：

```bash
export NACOS_HOME=/path/to/nacos-2.2.3
bash start-all.sh
```

停止服务：

```bash
bash stop-all.sh
```

同时停止 Docker 中间件：

```bash
bash stop-all.sh --with-docker
```

中间件定义位于 [`shop-parent/docker-compose.yml`](shop-parent/docker-compose.yml)，包括 MySQL 8、Redis 6 和 RabbitMQ Management。Nacos 当前需要单独准备，不在 Compose 中启动。

### Windows 使用说明

启动脚本是 Bash 脚本，建议使用 Git Bash 或 WSL 执行。也可以在 IDE 中直接启动 `shop-gateway`、`shop-user`、`shop-product` 和 `shop-order`。如果 Maven Wrapper 在本机环境解析失败，使用已安装的 Maven 3.9+ 或 IDE Maven 配置即可。

## 访问地址

| 服务 | 地址 | 说明 |
| --- | --- | --- |
| Vue 前端 | <http://localhost:5173> | 管理后台/业务页面 |
| Gateway | <http://localhost:8080> | 前端和外部 API 入口 |
| 用户服务文档 | <http://localhost:8081/doc.html> | Knife4j |
| 商品服务文档 | <http://localhost:8082/doc.html> | Knife4j |
| 订单服务文档 | <http://localhost:8083/doc.html> | Knife4j |
| Nacos 控制台 | <http://localhost:8848/nacos> | 默认账号 `nacos/nacos` |
| RabbitMQ 控制台 | <http://localhost:15672> | 默认账号 `guest/guest` |
| AI API 文档 | <http://localhost:8000/docs> | 可选，调试入口 |

本地演示账号：`admin / 123456`。生产环境请替换默认账号、JWT 密钥、内部令牌和中间件密码。

## 测试与验证

### 单元测试

```bash
# 根目录单体模块
mvn test

# 微服务模块
cd shop-parent
mvn test
```

当前仓库基线中，根目录测试和 `shop-parent` 测试分别覆盖 15 和 33 个测试用例；实际数量以 Maven 当前输出为准。

### 需要运行服务的验证脚本

启动对应服务和中间件后，可按需执行：

```bash
# 微服务冒烟
bash shop-parent/scripts/smoke-test-ms.sh

# 库存/订单并发验证
bash shop-parent/scripts/concurrency-test-ms.sh

# AI 客服冒烟
bash ecommerce-agent-python/scripts/smoke_test.sh

# Vue 页面检查
node shop-web/e2e-check.js
```

这些脚本用于验证当前可运行环境，不替代对极端宕机、网络分区和跨服务事务的完整生产级演练。

## 关键配置

开发环境可以参考各模块的 `application.yml`、`.env.example` 和 [`shop-parent/README.md`](shop-parent/README.md)。常见配置包括：

| 配置 | 用途 |
| --- | --- |
| `JWT_SECRET` | JWT 签名密钥，生产环境必须使用高强度随机值 |
| `INTERNAL_TOKEN` | 微服务内部调用校验 |
| `NACOS_ADDR` | Nacos 地址，默认本地 8848 |
| `MYSQL_*` | MySQL 连接信息 |
| `REDIS_*` | Redis 连接信息 |
| `RABBITMQ_*` | RabbitMQ 连接信息 |
| `LLM_API_KEY` | AI 客服调用模型所需密钥 |

`start-all.sh` 会为本地演示注入开发用 JWT 密钥和内部令牌；不要把这些值用于生产环境，也不要把真实密钥提交到 Git。

## 项目边界

这是求职展示项目，明确不覆盖以下生产级能力：

- 不承诺远程库存调用在“远端已提交但响应丢失、随后本地 JVM 崩溃”等极端窗口下的 Exactly Once。
- 没有引入 Outbox、Saga、Seata 或完整分布式事务日志；当前通过条件更新、幂等、补偿和重试覆盖常见失败场景。
- 不包含跨机房容灾、Kubernetes、完整监控平台和大规模压测体系。
- 微信登录、支付和部分售后流程以演示为主。
- AI 检索没有引入交叉编码器重排，中文分词和向量库配置也以小规模演示为目标。

这些限制是有意保留的，便于在面试中清楚说明“已经解决的问题”和“如果继续生产化需要补充的架构”。

## 相关文档

- [微服务运行与设计说明](shop-parent/README.md)
- [AI 客服说明](ecommerce-agent-python/README.md)
- [微信小程序说明](ecommerce-mini-program/README.md)
- [本地运行指南](本地运行指南.md)
- [工程记录：功能与安全修复](工程记录_功能与安全修复.md)
- [工程记录：审计与修复总览](工程记录_审计与修复总览.md)
- [简历项目表述](简历_修订版.md)

## License

仅用于学习、求职展示和技术交流。
