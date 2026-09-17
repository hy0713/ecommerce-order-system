#!/usr/bin/env bash
# =============================================================
# 统一构建脚本（防三坑：文件锁导致 repackage 失败、静默吞错、
# 构建失败后误跑旧 jar）
# 用法：bash scripts/build.sh
# =============================================================
set -u
cd "$(dirname "$0")/.."

export PATH="/e/cscode/java/tools/maven/bin:$PATH"
export MAVEN_OPTS="-Djava.net.preferIPv4Stack=true"

# 1. 按端口精准停止业务服务（不误杀 Nacos 8848 / RabbitMQ 等）
echo "[1/4] 停止运行中的业务服务（8080-8083）..."
for port in 8080 8081 8082 8083; do
  pid=$(netstat -ano 2>/dev/null | grep ":$port .*LISTENING" | awk '{print $5}' | head -1)
  if [ -n "${pid:-}" ] && [ "$pid" != "0" ]; then
    taskkill //F //PID "$pid" >/dev/null 2>&1 && echo "  已停止 :$port (pid $pid)"
  fi
done
sleep 2

# 2. 删除旧构建产物，防止失败后误跑旧 jar
echo "[2/4] 清理旧 jar..."
rm -f shop-common/target/*.jar shop-gateway/target/*.jar \
      shop-user/target/*.jar shop-product/target/*.jar shop-order/target/*.jar 2>/dev/null

# 3. 完整构建（保留全部输出）
echo "[3/4] 构建（完整输出）..."
mvn -B clean install -DskipTests 2>&1 | tee /tmp/shop-build.log

# 4. 显式校验：成功则打印产物，失败则退出非零
echo "[4/4] 校验..."
if grep -q "BUILD SUCCESS" /tmp/shop-build.log; then
  echo "✅ BUILD SUCCESS"
  ls -la shop-common/target/*.jar shop-gateway/target/*.jar \
        shop-user/target/*.jar shop-product/target/*.jar shop-order/target/*.jar 2>/dev/null
else
  echo "❌ BUILD FAILURE — 完整日志见 /tmp/shop-build.log"
  exit 1
fi
