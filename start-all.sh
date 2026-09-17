#!/usr/bin/env bash
# =============================================================
# 一键启动本地演示环境（阶段二微服务 + AI 智能客服 + Vue 管理后台）
#
# 用法：bash start-all.sh
# 停止：bash stop-all.sh
#
# 启动顺序：中间件 → 数据库迁移 → Nacos → 4 个微服务 → AI Agent → 管理后台
# 说明：脚本会做健康检查，逐步等待，失败时给出明确提示而不是静默继续。
#       本机环境特有的坑（代理、/tmp 路径）已在脚本内处理。
# =============================================================
set -u

ROOT="$(cd "$(dirname "$0")" && pwd)"
LOGS="$ROOT/logs"
PIDS="$ROOT/logs/pids"
mkdir -p "$LOGS" "$PIDS"

# 本机存在 http_proxy，Windows 原生 curl 访问 localhost 会被代理拦成 502
export no_proxy='*'
export NO_PROXY='*'
# 清理宿主注入的端口变量。
# 本机沙箱环境会注入 SERVER__PORT=7232（WorkBuddy 自身的服务代理端口），
# 而 Spring Boot 中「环境变量优先级高于 application.yml」，于是 Nacos 与 4 个微服务
# 会全部去抢 7232，实测 Nacos 直接报 `Port 7232 was already in use` 起不来。
# 这里显式清掉，保证各服务按各自 yml 里的 server.port 启动。
unset SERVER__PORT SERVER_PORT 2>/dev/null || true

# JWT 签名密钥：配置里**没有默认值**（缺失即 fail-fast），本地演示由这里注入。
# 这是刻意设计——HS256 是对称签名，密钥公开则任何人都能自签 token 冒充任意用户（含 ADMIN），
# 而仓库里的默认值就是公开的，"部署时忘了配"会变成静默的越权入口。
# 生产环境请通过环境变量提供真实密钥（>= 32 字节），例如：
#   export JWT_SECRET='<用 openssl rand -base64 48 生成的值>'
export JWT_SECRET="${JWT_SECRET:-local-dev-change-me-jwt-secret-please-override-in-production}"
# 服务间调用令牌（X-Internal-Token）：它决定业务服务是否相信请求"来自网关"，
# 而下游完全信任 X-User-Id —— 留空等于直连服务端口即可伪造身份冒充任意用户（含 ADMIN）。
# 因此配置里不留默认值，缺失时网关与业务服务都会**直接拒绝启动**（InternalTokenGuard），
# 不存在"留空跳过校验"的降级路径。单独启动某个服务时请自行 export INTERNAL_TOKEN=...
export INTERNAL_TOKEN="${INTERNAL_TOKEN:-local-dev-change-me-internal-token}"
# Windows 原生 curl 读不到 MSYS 的 /tmp 路径
TMPD=$(cygpath -w /tmp 2>/dev/null || echo "${TEMP:-/tmp}")
# 统一时区，避免调度阈值错 6 小时
TZ_OPT="-Duser.timezone=Asia/Shanghai"

# 可按需覆盖
NACOS_HOME="${NACOS_HOME:-/e/cscode/java/tools/nacos-2.2.3}"
WEB_PORT="${WEB_PORT:-5173}"

C_OK=$'\033[32m'; C_WARN=$'\033[33m'; C_ERR=$'\033[31m'; C_OFF=$'\033[0m'
ok()   { echo "  ${C_OK}✓${C_OFF} $1"; }
warn() { echo "  ${C_WARN}!${C_OFF} $1"; }
err()  { echo "  ${C_ERR}✗${C_OFF} $1"; }
step() { echo ""; echo "── $1"; }

# 等待某个 TCP 端口开始监听
wait_port() { # wait_port PORT NAME TIMEOUT_SEC
  local port=$1 name=$2 timeout=${3:-120} i=0
  while [ $i -lt "$timeout" ]; do
    if netstat -ano 2>/dev/null | grep ":$port " | grep -q LISTENING; then
      ok "$name 已就绪（${i}s）"; return 0
    fi
    sleep 2; i=$((i + 2))
  done
  err "$name 启动超时（${timeout}s），请查看 $LOGS 下日志"; return 1
}

# 等待 HTTP 探活通过
wait_http() { # wait_http URL NAME KEYWORD TIMEOUT_SEC
  local url=$1 name=$2 kw=$3 timeout=${4:-120} i=0
  while [ $i -lt "$timeout" ]; do
    if curl -s -m 4 --noproxy '*' "$url" 2>/dev/null | grep -q "$kw"; then
      ok "$name 探活通过（${i}s）"; return 0
    fi
    sleep 2; i=$((i + 2))
  done
  err "$name 探活超时（${timeout}s）"; return 1
}

start_bg() { # start_bg NAME LOGFILE CWD COMMAND...
  local name=$1 logfile=$2 cwd=$3; shift 3
  ( cd "$cwd" && nohup "$@" > "$logfile" 2>&1 & echo $! > "$PIDS/$name.pid" )
  sleep 3
  # 启动即失败要立刻暴露，不要在后面干等超时
  local pid; pid=$(cat "$PIDS/$name.pid" 2>/dev/null)
  if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then
    err "$name 启动即退出，日志：$logfile"
    sed -n '1,6p' "$logfile" 2>/dev/null | sed 's/^/     /'
    return 1
  fi
  return 0
}

echo "=========================================================="
echo " 轻量电商订单全系统 · 本地启动"
echo " 项目根目录：$ROOT"
echo "=========================================================="

# ── 0. 前置检查 ────────────────────────────────────────────────
step "0. 前置环境检查"
command -v java >/dev/null 2>&1 && ok "Java: $(java -version 2>&1 | head -1 | cut -d'"' -f2)" || { err "未找到 java（需要 JDK 17）"; exit 1; }
command -v node >/dev/null 2>&1 && ok "Node: $(node -v)" || { err "未找到 node（需要 18+）"; exit 1; }
command -v docker >/dev/null 2>&1 && ok "Docker: $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo '守护进程未运行')" || { err "未找到 docker"; exit 1; }

docker version --format '{{.Server.Version}}' >/dev/null 2>&1 || {
  warn "Docker 守护进程未运行，请先启动 Docker Desktop 后重跑本脚本"; exit 1;
}
AGENT_PY="$ROOT/ecommerce-agent-python/.venv/Scripts/python.exe"
[ -f "$AGENT_PY" ] && ok "Agent 虚拟环境：ecommerce-agent-python/.venv" || warn "未找到 Agent 虚拟环境（跳过 AI 客服启动）"

# ── 1. 中间件 ─────────────────────────────────────────────────
step "1. 启动中间件（MySQL / Redis / RabbitMQ）"
# 本机 docker compose 子命令不可用，需用 standalone 的 docker-compose
COMPOSE_BIN="docker-compose"
command -v "$COMPOSE_BIN" >/dev/null 2>&1 || COMPOSE_BIN="docker compose"
( cd "$ROOT/shop-parent" && $COMPOSE_BIN up -d ) >"$LOGS/compose.log" 2>&1 && ok "compose 启动完成" || { err "compose 启动失败，见 $LOGS/compose.log"; exit 1; }

# Redis Stack（Agent 向量库，独立 6380 端口）
if docker ps -a --format '{{.Names}}' | grep -q '^redis-stack$'; then
  docker start redis-stack >/dev/null 2>&1 && ok "Redis Stack 已启动（6380）"
else
  warn "未找到 redis-stack 容器；AI 客服的 RAG 能力将降级"
fi

step "等待中间件健康检查"
wait_port 3306 "MySQL" 120 || exit 1
wait_port 6379 "Redis" 60
wait_port 5672 "RabbitMQ" 60
[ -f "$AGENT_PY" ] && wait_port 6380 "Redis Stack" 60

# ── 2. 数据库增量迁移（幂等，可重复执行）────────────────────────
step "2. 数据库增量迁移"
if docker exec -i ecommerce-mysql mysql --default-character-set=utf8mb4 -uroot -proot123456 \
     < "$ROOT/sql/migration_2026_09.sql" >"$LOGS/migration.log" 2>&1; then
  ok "迁移已应用（user.role / stock_compensation）"
else
  err "迁移失败，见 $LOGS/migration.log"; exit 1
fi

# ── 3. Nacos 注册中心 ─────────────────────────────────────────
step "3. 启动 Nacos 注册中心"
if netstat -ano 2>/dev/null | grep ":8848 " | grep -q LISTENING; then
  ok "Nacos 已在运行"
elif [ -f "$NACOS_HOME/target/nacos-server.jar" ]; then
  start_bg nacos "$LOGS/nacos.log" "$NACOS_HOME" \
    java $TZ_OPT -Dnacos.standalone=true -Xms512m -Xmx512m -jar target/nacos-server.jar
  wait_http "http://127.0.0.1:8848/nacos/v1/console/health/readiness" "Nacos" "OK" 90 || exit 1
else
  err "未找到 Nacos：$NACOS_HOME/target/nacos-server.jar"
  echo "     可通过环境变量 NACOS_HOME 指定路径后重跑：NACOS_HOME=/path/to/nacos-2.2.3 bash start-all.sh"
  exit 1
fi

# ── 4. 四个微服务 ─────────────────────────────────────────────
step "4. 启动微服务"
# 注意：单体（阶段一）与网关都监听 8080，二者不能同时运行
for spec in "shop-user:8081" "shop-product:8082" "shop-order:8083" "shop-gateway:8080"; do
  name="${spec%%:*}"; port="${spec##*:}"
  if netstat -ano 2>/dev/null | grep ":$port " | grep -q LISTENING; then
    warn "$name 已在运行（$port）"
    continue
  fi
  jar="$ROOT/shop-parent/$name/target/$name-1.0.0.jar"
  if [ ! -f "$jar" ]; then
    err "$name 未构建：$jar"
    echo "     请先执行：cd shop-parent && mvn clean package -DskipTests"
    exit 1
  fi
  # 关键：-jar 的参数必须是 Windows 路径，Windows 版 java 认不了 MSYS 的 /e/xxx
  start_bg "$name" "$LOGS/$name.log" "$ROOT/shop-parent" \
    java $TZ_OPT -jar "$(cygpath -w "$jar")" || exit 1
  ok "$name 启动中（$port）"
done

for spec in "shop-user:8081" "shop-product:8082" "shop-order:8083" "shop-gateway:8080"; do
  wait_port "${spec##*:}" "${spec%%:*}" 120
done

step "确认 Nacos 注册"
sleep 5
REGS=$(curl -s -m 5 --noproxy '*' "http://127.0.0.1:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=20" 2>/dev/null)
echo "     已注册服务：$REGS"
echo "$REGS" | grep -q "shop-gateway" && ok "微服务注册完成" || warn "注册信息不完整，请检查各服务日志"

# ── 5. AI 智能客服 ────────────────────────────────────────────
step "5. 启动 AI 智能客服（Python）"
if [ ! -f "$AGENT_PY" ]; then
  warn "跳过：未找到 ecommerce-agent-python/.venv"
  echo "     如需启动：cd ecommerce-agent-python && py -3.12 -m venv .venv && .venv/Scripts/pip install -r requirements.txt"
elif netstat -ano 2>/dev/null | grep ":8000 " | grep -q LISTENING; then
  ok "Agent 已在运行（8000）"
else
  start_bg agent "$LOGS/agent.log" "$ROOT/ecommerce-agent-python" \
    "$(cygpath -w "$AGENT_PY")" -m uvicorn app.main:app --host 127.0.0.1 --port 8000
  # 只监听回环：Agent 的身份来自网关注入的 X-User-Id，必须经网关访问。
  # 监听 0.0.0.0 会让同网段任何机器直接调用它（虽拿不到身份，但能白耗大模型额度）。
  wait_http "http://127.0.0.1:8000/api/agent/health" "AI 客服" '"healthy":true' 120 || warn "Agent 未完全就绪，/health 可能显示部分依赖不可用"
fi

# ── 6. Vue 管理后台 ───────────────────────────────────────────
step "6. 启动 Vue 管理后台"
if netstat -ano 2>/dev/null | grep ":$WEB_PORT " | grep -q LISTENING; then
  ok "管理后台已在运行（$WEB_PORT）"
else
  VITE_BIN="$ROOT/shop-web/node_modules/vite/bin/vite.js"
  if [ -f "$VITE_BIN" ]; then
    start_bg web "$LOGS/web.log" "$ROOT/shop-web" node node_modules/vite/bin/vite.js --port "$WEB_PORT"
    wait_port "$WEB_PORT" "管理后台" 60
  else
    warn "未找到 vite，跳过管理后台（先在 shop-web 执行 npm install）"
  fi
fi

# ── 汇总 ──────────────────────────────────────────────────────
echo ""
echo "=========================================================="
echo " 启动完成"
echo "=========================================================="
cat <<EOF
  管理后台      http://localhost:$WEB_PORT        （用 localhost，不要用 127.0.0.1）
  网关 API      http://localhost:8080
  Nacos 控制台  http://localhost:8848/nacos       （nacos / nacos）
  RabbitMQ 控制台 http://localhost:15672          （guest / guest）
  AI 客服文档   http://localhost:8000/docs
  接口文档      http://localhost:8081/doc.html  各服务 8081/8082/8083 同理

  演示账号      admin / 123456   （角色 ADMIN，可发货/完成/看统计）

  常用命令
    bash scripts/smoke-test.sh              阶段一单体全链路
    bash scripts/concurrency-test.sh        阶段一并发无超卖
    bash shop-parent/scripts/smoke-test-ms.sh       阶段二微服务全链路
    bash shop-parent/scripts/concurrency-test-ms.sh 阶段二并发无超卖
    cd ecommerce-agent-python && bash scripts/smoke_test.sh   AI 客服全链路

  停止       bash stop-all.sh
  日志目录   logs/

  注意：阶段一单体与阶段二网关都占用 8080，不能同时启动。
        启动单体请先执行 bash stop-all.sh，再执行下面这行（env -u 是为了清掉宿主注入的 SERVER__PORT，
        否则 Spring Boot 会因环境变量优先级更高而抢用 7232 端口）：
          env -u SERVER__PORT -u SERVER_PORT \\
            JWT_SECRET="${JWT_SECRET:-local-dev-change-me-jwt-secret-please-override-in-production}" \\
            java -jar target/helpbydsv4.jar
EOF
