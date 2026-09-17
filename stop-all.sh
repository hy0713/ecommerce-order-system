#!/usr/bin/env bash
# =============================================================
# 停止本地演示环境（保留 Docker 中间件，可用 --with-docker 一并停止）
#
# 用法：bash stop-all.sh              # 只停应用（Nacos / 微服务 / Agent / 前端）
#       bash stop-all.sh --with-docker # 连中间件容器一起停
# =============================================================
set -u

ROOT="$(cd "$(dirname "$0")" && pwd)"
PIDS="$ROOT/logs/pids"
WITH_DOCKER=0
[ "${1:-}" = "--with-docker" ] && WITH_DOCKER=1

echo "=========================================================="
echo " 停止本地演示环境"
echo "=========================================================="

# 1. 优先按记录的 PID 精确停止
#    注意：start-all.sh 记录的是 MSYS 侧 PID，须用 MSYS 的 kill（taskkill 只认 Windows PID）
if [ -d "$PIDS" ]; then
  for f in "$PIDS"/*.pid; do
    [ -f "$f" ] || continue
    name=$(basename "$f" .pid)
    pid=$(cat "$f" 2>/dev/null)
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      kill -9 "$pid" 2>/dev/null && echo "  已停止 $name (PID=$pid)"
    fi
    rm -f "$f"
  done
fi

# 2. 兜底：按端口清理（覆盖不是本脚本启动的进程）
for spec in "8000:AI 客服" "5173:管理后台" "8080:网关" "8081:shop-user" \
            "8082:shop-product" "8083:shop-order" "8848:Nacos"; do
  port="${spec%%:*}"; name="${spec##*:}"
  pid=$(netstat -ano 2>/dev/null | grep ":$port " | grep LISTENING | head -1 | awk '{print $NF}')
  if [ -n "$pid" ]; then
    MSYS_NO_PATHCONV=1 taskkill /F /PID "$pid" >/dev/null 2>&1 \
      && echo "  已停止 $name (端口 $port, PID=$pid)" || echo "  停止 $name 失败 (PID=$pid)"
  fi
done

# 3. 单体（阶段一）也占 8080，一并兜底
pid=$(netstat -ano 2>/dev/null | grep ":8080 " | grep LISTENING | head -1 | awk '{print $NF}')
[ -n "$pid" ] && MSYS_NO_PATHCONV=1 taskkill /F /PID "$pid" >/dev/null 2>&1 && echo "  已停止 8080 上的进程 (PID=$pid)"

# 4. 可选：停止中间件容器
if [ "$WITH_DOCKER" = "1" ]; then
  COMPOSE_BIN="docker-compose"
  command -v "$COMPOSE_BIN" >/dev/null 2>&1 || COMPOSE_BIN="docker compose"
  ( cd "$ROOT/shop-parent" && $COMPOSE_BIN stop ) >/dev/null 2>&1 && echo "  已停止中间件容器（mysql/redis/rabbitmq）"
  docker stop redis-stack >/dev/null 2>&1 && echo "  已停止 redis-stack"
else
  echo "  保留中间件容器（如需一并停止：bash stop-all.sh --with-docker）"
fi

echo ""
echo "剩余监听端口："
netstat -ano 2>/dev/null | grep LISTENING | grep -E ":(8848|8080|8081|8082|8083|8000|5173)\b" | awk '{print "  " $2}' | sort -u || echo "  （应用端口已全部释放）"
