#!/usr/bin/env bash
# =============================================================
# 一键启动脚本（本地开发 / 演示用）
#
# 用法：
#   bash scripts/start-all.sh mono     # 阶段一单体（8080）+ 中间件 + Agent，最省内存，推荐演示用
#   bash scripts/start-all.sh micro    # 阶段二微服务（网关8080 + 3 服务）+ Nacos + 中间件
#   bash scripts/start-all.sh all      # 微服务 + Agent（内存吃紧，见下方说明）
#   bash scripts/start-all.sh stop     # 停止全部
#
# 内存提示：每个 JVM 默认限制 -Xmx256m。若机器内存 < 16GB，
#   建议一次只跑 mono 或 micro，不要 mono/micro 同时跑（都占 8080）。
#
# 启动日志：logs/<name>.log（GBK 编码，Linux/macOS 下需 iconv 转换）
# =============================================================
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGS="$ROOT/logs"
mkdir -p "$LOGS"

MODE="${1:-mono}"
# 本机若配置了 http_proxy，curl 访问 localhost 会被代理拦截（502）
export no_proxy='*'
export NO_PROXY='*'
# Windows 上 JVM 默认时区可能不是东八区，会导致调度阈值偏差
TZ_OPT="-Duser.timezone=Asia/Shanghai"
JVM_OPT="-Xms128m -Xmx256m"

info() { printf '\033[32m[启动]\033[0m %s\n' "$1"; }
warn() { printf '\033[33m[注意]\033[0m %s\n' "$1"; }
err()  { printf '\033[31m[失败]\033[0m %s\n' "$1"; }

wait_http() { # wait_http URL 期望子串 描述 最大秒数
  local url="$1" expect="$2" desc="$3" limit="${4:-90}" i=0
  while [ "$i" -lt "$limit" ]; do
    if curl -s -m 3 --noproxy '*' "$url" 2>/dev/null | grep -q "$expect"; then
      info "$desc 就绪（${i}s）"; return 0
    fi
    i=$((i + 2)); sleep 2
  done
  err "$desc 在 ${limit}s 内未就绪，请查看 $LOGS"; return 1
}

wait_port() { # wait_port 端口 描述 最大秒数
  local port="$1" desc="$2" limit="${3:-120}" i=0
  while [ "$i" -lt "$limit" ]; do
    if netstat -ano 2>/dev/null | grep LISTENING | grep -q ":$port "; then
      info "$desc 已监听 $port（${i}s）"; return 0
    fi
    i=$((i + 3)); sleep 3
  done
  err "$desc 端口 $port 未监听，请查看 $LOGS"; return 1
}

start_bg() { # start_bg 名称 命令...
  local name="$1"; shift
  ( nohup "$@" > "$LOGS/$name.log" 2>&1 & echo $! > "$LOGS/$name.pid" )
  info "$name 已启动（PID $(cat "$LOGS/$name.pid" 2>/dev/null)）"
}

stop_all() {
  info "停止 Java / Python 业务进程..."
  for f in "$LOGS"/*.pid; do
    [ -f "$f" ] || continue
    local pid; pid=$(cat "$f")
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      MSYS_NO_PATHCONV=1 taskkill /F /PID "$pid" >/dev/null 2>&1 || kill -9 "$pid" 2>/dev/null
      info "已停止 $(basename "$f" .pid)（PID $pid）"
    fi
    rm -f "$f"
  done
  info "中间件容器保持运行；如需一并停止：cd shop-parent && docker-compose down"
}

case "$MODE" in
  stop) stop_all; exit 0 ;;
  mono|micro|all) ;;
  *) err "未知模式：$MODE（可选 mono / micro / all / stop）"; exit 1 ;;
esac

# ---------- 1. 中间件 ----------
info "启动中间件容器（mysql / redis / rabbitmq）..."
( cd "$ROOT/shop-parent" && docker-compose up -d ) || { err "docker-compose 启动失败，请确认 Docker Desktop 已运行"; exit 1; }

info "启动 Redis Stack（Agent 向量库，6380）..."
docker start redis-stack >/dev/null 2>&1 || warn "redis-stack 容器不存在，Agent 的 RAG 能力将降级"

info "等待 MySQL / Redis / RabbitMQ 健康..."
for i in $(seq 1 30); do
  healthy=$(docker ps --filter "name=ecommerce-" --format '{{.Status}}' | grep -c healthy)
  [ "$healthy" -ge 3 ] && { info "3 个中间件容器全部 healthy"; break; }
  [ "$i" -eq 30 ] && warn "中间件未全部 healthy，后续可能连接失败"
  sleep 3
done

# ---------- 2. 数据库升级检查 ----------
COLS=$(docker exec ecommerce-mysql mysql -uroot -proot123456 -N -e \
  "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='ecommerce' AND TABLE_NAME='user' AND COLUMN_NAME='role';" 2>/dev/null | tr -d '\r')
if [ "${COLS:-0}" = "0" ]; then
  warn "检测到旧数据卷（user 表缺 role 列），执行增量迁移..."
  docker exec -i ecommerce-mysql mysql --default-character-set=utf8mb4 -uroot -proot123456 < "$ROOT/sql/migration_2026_09.sql" \
    && info "迁移完成" || err "迁移失败，请手工执行 sql/migration_2026_09.sql"
fi

# ---------- 3. 阶段一单体 ----------
if [ "$MODE" = "mono" ]; then
  JAR="$ROOT/target/helpbydsv4.jar"
  [ -f "$JAR" ] || { warn "未找到 $JAR，先执行构建..."; ( cd "$ROOT" && ./mvnw -o -B package -DskipTests ) || { err "构建失败"; exit 1; }; }
  start_bg monolith java $TZ_OPT $JVM_OPT -jar "$JAR"
  wait_http "http://127.0.0.1:8080/api/category/tree" '"code":200' "单体后端 8080" 120
  echo
  info "单体模式启动完成：接口文档 http://localhost:8080/doc.html（admin / 123456）"
fi

# ---------- 4. 阶段二微服务 ----------
if [ "$MODE" = "micro" ] || [ "$MODE" = "all" ]; then
  NACOS_HOME="${NACOS_HOME:-/e/cscode/java/tools/nacos-2.2.3}"
  if [ -f "$NACOS_HOME/target/nacos-server.jar" ]; then
    if curl -s -m 3 --noproxy '*' http://127.0.0.1:8848/nacos/ >/dev/null 2>&1; then
      info "Nacos 已在运行，跳过启动"
    else
      start_bg nacos java $TZ_OPT -Dnacos.standalone=true -Xms256m -Xmx512m -jar "$NACOS_HOME/target/nacos-server.jar"
      wait_http "http://127.0.0.1:8848/nacos/v1/console/health/readiness" 'OK' "Nacos 8848" 120
    fi
  else
    err "未找到 Nacos：$NACOS_HOME/target/nacos-server.jar（可用环境变量 NACOS_HOME 指定）"; exit 1
  fi

  P="$ROOT/shop-parent"
  for m in shop-user shop-product shop-order shop-gateway; do
    JAR="$P/$m/target/$m-1.0.0.jar"
    [ -f "$JAR" ] || { warn "未找到 $JAR，先执行构建..."; ( cd "$P" && ./mvnw -o -B package -DskipTests ) || { err "构建失败"; exit 1; }; }
    start_bg "$m" java $TZ_OPT $JVM_OPT -jar "$JAR"
  done

  wait_http "http://127.0.0.1:8080/api/category/tree" '"code":200' "网关 8080" 150
  echo
  info "微服务模式启动完成："
  echo "    网关 8080 / 用户 8081 / 商品 8082 / 订单 8083"
  echo "    Nacos 控制台 http://localhost:8848/nacos（nacos / nacos）"
  echo "    接口文档 http://localhost:8081/doc.html 等"
fi

# ---------- 5. Agent ----------
if [ "$MODE" = "all" ]; then
  A="$ROOT/ecommerce-agent-python"
  if [ -f "$A/.venv/Scripts/python.exe" ]; then
    ( cd "$A" && nohup no_proxy='*' NO_PROXY='*' ./.venv/Scripts/python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000 \
        > "$LOGS/agent.log" 2>&1 & echo $! > "$LOGS/agent.pid" )
    info "Agent 已启动（PID $(cat "$LOGS/agent.pid" 2>/dev/null)）"
    wait_http "http://127.0.0.1:8000/api/agent/health" '"healthy":true' "AI Agent 8000" 150
    echo "    接口文档 http://localhost:8000/docs"
  else
    warn "未找到 Agent 虚拟环境（ecommerce-agent-python/.venv），跳过。"
    warn "首次使用请：py -3.12 -m venv .venv && pip install -r requirements.txt"
  fi
fi

echo
info "全部就绪。日志目录：$LOGS"
echo "    停止服务：bash scripts/start-all.sh stop"
