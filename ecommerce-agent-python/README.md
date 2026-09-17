# 电商智能客服 Agent 系统（Python 版）

RAG 检索增强 + 工具调用的智能客服，与「轻量电商订单系统」（shop-parent 微服务）业务联动，
通过 HTTP 接口对接电商网关，面向校招全栈演示场景。

## 技术栈（版本锁定，见 requirements.txt 内注释）

| 分类 | 选型 | 版本 |
|---|---|---|
| Web | FastAPI / uvicorn | 0.104.1 / 0.27.1 |
| ORM | SQLAlchemy(async) / aiomysql | 2.0.23 / 0.2.0 |
| Agent | LangChain / langchain-openai | 0.1.0 / 0.0.3 |
| 大模型 | DeepSeek（OpenAI 兼容协议） | deepseek-chat |
| Embedding | fastembed + bge-small-zh-v1.5（本地离线） | 0.2.6 |
| 向量库 | Redis Stack 7.2（RediSearch，:6380） | redis-py 5.0.1 |
| MQ | RabbitMQ（pika） | 1.3.2 |
| Python | 3.12（3.14 不兼容 langchain 0.1.0 时代依赖） | - |

## 快速启动

```bash
# 0. 前置：MySQL(:3306) + RabbitMQ(:5672)（电商系统已有）；启动 Redis Stack 独立容器
docker run -d --name redis-stack -p 6380:6379 -p 8001:8001 redis/redis-stack-server:7.2.0-v10

# 1. 环境（首次）
py -3.12 -m venv .venv
source .venv/Scripts/activate
pip install -r requirements.txt

# 2. 预热 embedding 模型（~100MB，走 hf-mirror，之后离线可用）
python scripts/prefetch_model.py

# 3. 配置 .env（从 .env.example 复制，填入 LLM_API_KEY）
cp .env.example .env

# 4. 启动（只监听回环：身份由网关注入，必须经网关访问，勿暴露到 0.0.0.0）
uvicorn app.main:app --host 127.0.0.1 --port 8000

# 5. 接口文档 / 探活（本机可直连查看 Swagger）
open http://localhost:8000/docs
curl http://localhost:8000/api/agent/health
```

> **调用入口是电商网关 8080**（网关有 `/api/agent/**` 路由转发到本服务）。
> 前端不要直连 :8000 —— 那样调用方可以自报 `user_id`，等于任意用户可读他人会话。
> 鉴权：`POST /api/agent/chat` 可选鉴权（游客可用），`/api/agent/session/**` 必须登录。

## 接口清单（前缀 /api/agent）

身份一律取自网关注入的 `X-User-Id`（见 `app/common/identity.py`），**接口不再接受 user_id 入参**。

| 接口 | 方法 | 鉴权 | 说明 |
|---|---|---|---|
| /api/agent/health | GET | 匿名 | 健康检查 |
| /api/agent/session/list | GET | 必须登录 | 当前用户会话列表 |
| /api/agent/session/create | POST | 必须登录 | 创建会话 {title?} |
| /api/agent/session/delete/{id} | DELETE | 必须登录 | 删除会话（级联清理，仅限本人） |
| /api/agent/session/{id}/messages | GET | 必须登录 | 会话历史消息（仅限本人） |
| /api/agent/chat | POST | 可选鉴权 | 对话 {session_id?, content, ecom_token?}；游客按 user_id=0 |
| /api/agent/knowledge/upload | POST | 上传文档（pdf/docx/txt/md ≤20MB） |
| /api/agent/knowledge/list | GET | 文档分页 |
| /api/agent/knowledge/delete/{id} | DELETE | 删除文档（联动清向量） |
| /api/agent/tool/list | GET | 工具配置列表 |
| /api/agent/tool/status/{id} | PUT | 工具启停 {status: 0/1} |

## 核心设计

- **Agent 编排**：create_openai_tools_agent + AgentExecutor（max_iterations=4），DeepSeek function calling；
  工具内部 catch-all 返回友好文案 → 对话永不中断；最外层兜底话术
- **RAG（混合检索）**：上传文档 → MQ 异步解析分块（500/100 递归字符）→ fastembed 向量化 → RediSearch
  （idx:agent_knowledge，COSINE；另有 tokens 字段供 BM25）→ **BM25 关键词路 + 向量路 → RRF 融合
  （score = Σ 1/(k+rank)，k=60）→ 向量路保留相似度阈值 0.7 → 按文档去重取 Top3**。
  加关键词路的原因：型号/订单号/政策编号这类**精确匹配型**查询，向量召回经常漏
  （`iPhone 15` 与 `iPhone 15 Pro` 向量几乎重合）；中文分词用自建 bigram
  （RediSearch 默认分词会把连续中文切成一个超长 token）。可用 `rag_hybrid_enabled=false` 回退纯向量。
- **3 个内置工具**：query_order_status（经网关按 token 用户过滤）/ query_product_stock（白名单免登录，
  ID 走 /fresh 实时直查）/ query_after_sale_policy（内部 RAG）
- **上下文记忆**：Redis `agent:ctx:{session_id}` 最近 5 轮，24h TTL；MySQL 经 MQ 异步落库（降级直写）
- **历史消息读一致性（重要）**：会话历史来自「Redis 热层 ∪ MySQL 归档层」的合并结果。
  MySQL 由 MQ 消费者异步写入，可能滞后于 `POST /chat` 的返回；只读 MySQL 会出现
  「刚发完消息、立刻刷新历史却是空的」。`session_service.list_messages` 会用 Redis 补齐
  归档层尚未落库的尾部消息（`pending=true` 标记），保证读己之写；
  去重按「归档层尾部与热层的连续片段匹配」实现，允许的最坏情况是短暂重复展示，绝不丢消息。
- **统一返回**：Result{code,message,data}（code=200 成功/401 未登录/500 失败），HTTP 恒 200
- **降级策略（逐项说明，勿夸大）**：
  | 依赖 | 不可用时的行为 |
  |---|---|
  | Redis | RAG 检索跳过，对话仍返回 |
  | RabbitMQ | 消息改为降级直写，对话仍返回 |
  | 电商网关 | 工具返回友好文案，对话仍返回 |
  | **MySQL** | **服务进程照常启动**（启动期重试约 6 秒后转入后台每 15 秒重试建表），依赖 MySQL 的接口返回 code=500；数据库恢复后自动转为正常模式 |
  `/health` 逐项上报 `mysql / schemaReady / redis / rabbitmq` 与综合 `healthy` 字段

## 与电商系统对接

- 网关 http://localhost:8080；商品/分类免登录；订单接口需 `Authorization: Bearer <token>`
- 前端把用户自己的电商 token 作为 ecom_token 传入 → 订单查询按该 token 的用户身份过滤
- 数据库复用电商 MySQL 实例（ecommerce 库，agent 4 表启动自动创建，utf8mb4）

## 排障（本机环境坑）

1. **必须 Python 3.12**：`py -3.12 -m venv .venv`；3.14 上 fastembed 0.2.6 无法安装
2. **Git Bash 中文乱码**：curl 请求体写文件后 `--data @file`；上传文件名用 ASCII（中文文件名经 Windows curl 转 GBK 乱码，浏览器上传无此问题）
3. **Redis Stack 必须 6380**：电商 Redis(6.2) 占用 6379，无向量能力，勿改端口
4. **模型下载慢**：prefetch 脚本已内置 HF_ENDPOINT=https://hf-mirror.com
5. **Maven/Docker 镜像等**：同电商仓库 README（docker.1panel.live 镜像先例）

### 实战踩坑记录（已修复，勿回退）

| 坑 | 现象 | 修复 |
|---|---|---|
| langchain 0.1.0 依赖矩阵 | langchain 0.1.0 要求 langsmith<0.1.0，与 langchain-core 0.1.24+（langsmith>=0.1.0）冲突；langchain-text-splitters 0.0.1 要求 core>=0.1.28 不可用 | core 锁 0.1.23 + langsmith 0.0.87；分块用 langchain 内置 langchain.text_splitter |
| pydantic 2.5.2 on 3.12 | langsmith v1 shim 报 `ForwardRef._evaluate() missing recursive_guard` | pydantic 升 2.7.4（实测修复线） |
| SQLAlchemy 2.0.23 + aiomysql | pool_pre_ping 下 `ping() missing reconnect`（复用的池连接才触发） | SQLAlchemy 2.0.28 + 方言 `_send_false_to_ping=True` |
| RediSearch KNN | `*=>[KNN...]` 报 Syntax error（dialect 1 不支持） | 检索命令加 `DIALECT 2` |
| @tool("中文") | langchain 0.1.0 把字符串当工具名，tool_name 变成中文 | `@tool("query_order_status")` 英文标识 + 中文 docstring |
| pika 生产者 | `queue.Queue.get(timeout)` 空队列抛 Empty 被误判连接故障死循环重连 | 捕获 queue.Empty 视为空闲继续 |

## 验收

```bash
bash scripts/smoke_test.sh   # 会话→对话→RAG→工具→清理 全链路
```
