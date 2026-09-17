# 轻量电商订单全栈系统

一个仓库覆盖「**单体 → 微服务 → 前端 → AI 智能客服**」四个部分的电商交易系统，完整实现
**商品 → 购物车 → 订单 → 库存** 业务闭环，约 1.8 万行代码（Java 10k+ / Python 2.6k / 前端 5.5k）。

演进路径是刻意的：先在一个单体里把业务闭环和事务边界跑通（阶段一），
再按业务域拆成微服务并补齐分布式场景下的并发与一致性（阶段二），
最后补管理后台、小程序与 AI 智能客服（阶段三 / 拓展）。

## 模块导航

| 模块 | 目录 | 内容 | 详细文档 |
|---|---|---|---|
| 阶段一 · 单体后端 | `src/` | Spring Boot 单体，商品/购物车/订单/库存完整闭环与事务边界 | [src/README.md](src/README.md) |
| 阶段二 · 微服务 | `shop-parent/` | user / product / order 三服务 + Gateway，Nacos / OpenFeign / Sentinel | [shop-parent/README.md](shop-parent/README.md) |
| 阶段三 · 管理后台 | `shop-web/` | Vue3 + Element Plus + Pinia，含手写 SVG 图表 | [shop-web/README.md](shop-web/README.md) |
| 阶段三 · 小程序 | `ecommerce-mini-program/` | uni-app（Vue3），商品浏览 / 购物车 / 订单 / 智能客服 | [README](ecommerce-mini-program/README.md) |
| 拓展 · AI 智能客服 | `ecommerce-agent-python/` | FastAPI + LangChain，RAG 混合检索 + Function Calling | [README](ecommerce-agent-python/README.md) |

端口约定：Gateway `8080` / user `8081` / product `8082` / order `8083` / AI 客服 `8000` /
管理后台 `5173` / Nacos `8848` / MySQL `3306` / Redis `6379` / RabbitMQ `5672` / Redis Stack（向量库）`6380`。

## 整体架构

```
                    ┌──────────────────┐   ┌──────────────────┐
   Vue3 管理后台 ───▶│                  │   │  uni-app 小程序   │
   (5173, /api 代理)│   Gateway :8080  │◀──│  (直连网关)       │
                    │  · JWT 全局鉴权   │   └──────────────────┘
                    │  · 身份头注入/剥离 │
                    │  · Sentinel 限流  │────────────┐
                    └────────┬─────────┘            │ /api/agent/**
                             │ lb://                ▼
              ┌──────────────┼──────────────┐  ┌─────────────────────┐
              ▼              ▼              ▼  │ AI 客服 (FastAPI)    │
        shop-user      shop-product    shop-order│ LangChain Agent    │
         :8081           :8082           :8083  │ · RAG 混合检索      │
          │                │              │    │ · 3 个 function tool│
          │◀── OpenFeign ──┴──────────────┘    └──────┬──────────────┘
          │       （内部令牌校验的跨服务调用）           │ 工具经网关调用电商接口
          └────────────┬───────────────────────────────┘
                       ▼
        MySQL(3306) · Redis(6379) · RabbitMQ(5672) · Redis Stack(6380)
                       ▲
                  Nacos(8848) 注册发现
```

## 核心设计

**并发与一致性**

- **防超卖分层**：Redisson 分布式锁收敛应用层竞争 + `SELECT ... FOR UPDATE` 行锁把「判断-扣减」串行化
  + `UPDATE ... WHERE stock >= ? AND version = ?` 数据库层兜底；行锁生效时版本冲突不可达，
  因此删除了历史上的乐观锁重试循环
- **订单超时取消**：RabbitMQ「TTL + 死信队列」实现延迟消息（不依赖插件），失败进归档队列由定时任务重试
- **跨服务补偿**：库存回补失败落 `stock_compensation` 补偿表（唯一键幂等 + `REQUIRES_NEW` 独立事务），
  由调度任务重试，保证最终一致性
- **状态流转幂等**：订单状态一律条件更新 `WHERE id=? AND order_status=?` 并校验受影响行数，
  禁止「先查状态再无条件更新」（并发双取消会重复回补库存）

**鉴权与安全边界**

- **匿名白名单按「HTTP 方法 + 路径」精确匹配**，禁止 `/api/xxx/**` 宽通配（曾因此放行商品写接口与库存接口），
  有单测防回归；端点分三类：匿名 / **可选鉴权**（游客可用、带 token 必须有效）/ 必须登录
- **网关剥离客户端伪造的 `X-User-Id` / `X-User-Role` / `X-Internal-Token`** 后注入认定值，
  并拒绝含路径穿越段的请求（`/api/product/../internal/...` 曾可绕过前缀检查直达内部接口）
- **内部接口隔离**：服务间接口一律 `/api/internal/` 前缀，网关不路由（404）；业务服务再校验
  `X-Internal-Token`，阻断绕过网关的直连请求
- **密钥 fail-fast**：JWT 密钥与内部令牌**都不提供仓库默认值**，缺失或长度不足直接拒绝启动
  （HS256 对称签名，密钥公开等于任何人可自签 token 冒充任意用户，网关验签拦不住）

**AI 智能客服**

- **RAG 混合检索**：BM25（关键词路）+ 向量 KNN（语义路）→ **RRF 融合**（`Σ weight/(k+rank)`，
  只用排名不用分数——余弦相似度与 BM25 量纲不可比）；中文用自建 bigram 切分
  （RediSearch 默认分词会把连续中文切成一个超长 token，BM25 命中不了）
- **Function Calling**：订单查询 / 商品库存 / 售后政策 3 个工具，经网关调用 Java 后端；
  身份由网关注入、工具的电商鉴权靠请求体 `ecom_token` 透传
- **可靠性**：RAG 失败静默降级、工具内部 catch-all、最外层兜底话术，保证任何异常下接口仍有回复

## 快速开始

```bash
# 一键启动：中间件容器 → 数据库迁移 → Nacos → 4 个微服务 → AI 客服 → 管理后台
# 脚本逐步健康检查，缺什么会明确提示；密钥与内部令牌由脚本注入本地开发值
bash start-all.sh

# 停止（--with-docker 连容器一起停）
bash stop-all.sh --with-docker
```

启动后：

| 入口 | 地址 | 账号 |
|---|---|---|
| 管理后台 | http://localhost:5173 | `admin / 123456`（ADMIN） |
| 接口文档（网关） | http://localhost:8080/doc.html | — |
| AI 客服文档 | http://localhost:8000/docs | — |

> 只起单体（阶段一）时端口与网关冲突，需先 `bash stop-all.sh`，
> 并按 [src/README.md](src/README.md) 显式提供 `JWT_SECRET`。

## 质量与验证

自动化验证覆盖四层，脚本都在仓库里，可复现：

| 套件 | 脚本 | 覆盖 |
|---|---|---|
| 单元测试 | `mvn test` | 白名单匹配规则（含越权与路径穿越防回归）、内部令牌守卫 |
| 微服务冒烟 | `shop-parent/scripts/smoke-test-ms.sh` | 注册→登录→地址→加购→下单→支付→发货→完成→取消→异常场景→鉴权边界→业务服务端口防护 |
| 并发压测 | `shop-parent/scripts/concurrency-test-ms.sh` | 20 并发×库存 5 → 恰好 5 单成功、库存 0、零超卖 |
| AI 客服全链路 | `ecommerce-agent-python/scripts/smoke_test.sh` | 健康检查→鉴权边界→会话→对话→文档上传→向量化→RAG 检索→工具调用→清理 |
| 浏览器端到端 | `shop-web/e2e-check.js` | CDP 驱动真实浏览器跑完整下单闭环与智能客服提问 |
| 检索链路诊断 | `ecommerce-agent-python/scripts/diag_retrieval.py` | 打印「切词 / 关键词路 / 向量路 / 融合结果」，定位召回问题 |

最近一次全量回归（2026-09-17，真实中间件环境）全部通过：
微服务冒烟 54 项、AI 客服 21 项、浏览器端到端 21 项、单元测试 18 项，
并发压测 20×5 零超卖。

## 目录结构

```
├── src/                         # 阶段一：Spring Boot 单体
├── shop-parent/                 # 阶段二：微服务（gateway / user / product / order / common）
├── shop-web/                    # 阶段三：Vue3 管理后台（含 e2e-check.js）
├── ecommerce-mini-program/      # 阶段三：uni-app 小程序
├── ecommerce-agent-python/      # 拓展：AI 智能客服（FastAPI + LangChain）
├── sql/                         # 建表 / 种子数据 / 增量迁移
├── scripts/                     # 冒烟与并发测试脚本
├── start-all.sh / stop-all.sh   # 一键启停本地演示环境
└── 本地运行指南.md               # 完整运行与排障说明
```

## 环境变量

密钥类均**无仓库默认值**，缺失即启动失败；本地由 `start-all.sh` 注入带 `change-me` 标记的开发值。

| 变量 | 默认值 | 用途 |
|---|---|---|
| `JWT_SECRET` | **无（缺失即启动失败）** | JWT HS256 密钥，≥32 字节 |
| `INTERNAL_TOKEN` | **无（缺失即启动失败）** | 服务间调用令牌 `X-Internal-Token` |
| `LLM_API_KEY` | **无（AI 客服需配置）** | DeepSeek API Key，见 `ecommerce-agent-python/.env.example` |
| `MYSQL_*` / `REDIS_*` / `RABBITMQ_*` | 本地开发值 | 中间件连接 |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,...` | 允许的前端来源（逗号分隔） |

## 注意事项（本机环境）

1. **Maven 网络**：本机 Java 解析 Maven Central 偶发失败，需 `MAVEN_OPTS="-Djava.net.preferIPv4Stack=true"`
2. **Maven Wrapper**：Git Bash 下 `./mvnw` 可能报找不到 classworlds 启动器，绕法是直连 java 调启动器
   ```bash
   D=~/.m2/wrapper/dists/apache-maven-3.9.9/*/; java -classpath "$D/boot/plexus-classworlds-2.8.0.jar" \
     "-Dclassworlds.conf=$D/bin/m2.conf" "-Dmaven.home=$D" \
     -Dmaven.multiModuleProjectDirectory="$PWD" \
     org.codehaus.plexus.classworlds.launcher.Launcher -o -B package
   ```
3. **Docker 镜像**：直连 Docker Hub 不通时可换镜像源
4. **Git Bash 传参**：向 Windows 原生程序传中文参数会被转成 GBK，curl 请求体请写入文件后 `--data @file`
5. **curl 走代理**：访问 localhost 可能被代理拦截返回 502，加 `--noproxy '*'`
6. **日志编码**：应用日志按平台编码（GBK）落盘，`grep` 搜中文会搜不到
7. **种子数据编码**：`sql/data.sql` 首行 `SET NAMES utf8mb4` 不可删除，否则 initdb 中文乱码
8. **数据库未就绪**：应用会重试后继续启动，此时依赖库的接口返回 500，请等中间件就绪
9. **compose 执行目录**：必须在 `shop-parent/` 下执行（根目录的 `docker-compose.yml` 是本项目早期的精简版）
10. **Nacos 端口 9848 被占用**：Windows 的保留端口段可能覆盖 `9848`（= 8848 + 1000，Nacos 的 gRPC 端口），
    表现为 `netstat` 看不到占用却 `bind` 失败。执行 `net stop winnat && net start winnat` 重新分配保留段即可

## 已知边界

诚实标注未做的部分，避免误读：

- **AI 客服的 rerank 未实现**：当前是「BM25 + 向量 + RRF」，没有 cross-encoder 精排；
  演示规模下 RRF 已解决"召回漏"的主要矛盾，精排收益要在更大候选集上才明显
- **中文分词是自建 bigram**，非专业分词器（不处理同义词与多字词边界），小规模知识库够用
- **向量库用 Redis Stack**：规模上来后应换 Milvus / pgvector
- **密钥未接配置中心**：目前靠环境变量注入，生产建议托管到 Nacos / KMS 并支持轮换
