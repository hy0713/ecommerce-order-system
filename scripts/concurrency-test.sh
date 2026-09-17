#!/usr/bin/env bash
# =============================================================
# 并发无超卖测试：20 个用户并发对库存=5 的商品（id=10）下单
# 断言：成功订单数=5，最终库存=0，订单明细=5（不超卖）
# 用法：bash scripts/concurrency-test.sh
# =============================================================
set -u
# 本机存在 http_proxy 环境变量，curl 访问 localhost 会被代理拦截（502），故绕过代理
export no_proxy='*'
export NO_PROXY='*'
BASE="http://localhost:8080/api"
PRODUCT_ID=10
TOTAL_USERS=20
# Windows 与 MSYS 均可见的临时目录（python/curl 都能访问）
TMPD=$(cygpath -w /tmp 2>/dev/null || echo "$TEMP")

echo "======================================================"
echo "并发测试：${TOTAL_USERS} 用户 × 库存5（商品 $PRODUCT_ID）"
echo "======================================================"

# 1. 清理历史测试数据并重置商品 10 库存为 5、版本号为 0
docker exec ecommerce-mysql mysql -uroot -proot123456 -e \
  "DELETE FROM ecommerce.order_detail; DELETE FROM ecommerce.order_master;
   UPDATE ecommerce.product_info SET stock=5, version=0 WHERE id=$PRODUCT_ID;" 2>/dev/null
echo "历史订单已清理，库存已重置为 5"

# 2. 准备用户：注册 + 登录 + 地址 + 加购（每个用户独立 token 与地址）
TOKENS=()
ADDRS=()
for i in $(seq 1 $TOTAL_USERS); do
  UN="conc_${i}_$(date +%s)"
  printf '{"username":"%s","password":"123456"}' "$UN" > "$TMPD/reg.json"
  curl -s -X POST "$BASE/user/register" -H "Content-Type: application/json" --data @"$TMPD/reg.json" > /dev/null
  TOKEN=$(curl -s -X POST "$BASE/auth/login" -H "Content-Type: application/json" --data @"$TMPD/reg.json" \
    | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
  printf '{"receiverName":"并发测试","receiverPhone":"13800000000","address":"测试地址","isDefault":1}' > "$TMPD/addr.json"
  ADDR=$(curl -s -X POST "$BASE/address" -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
    --data @"$TMPD/addr.json" | python -c "import sys,json;print(json.load(sys.stdin)['data'])")
  printf '{"productId":%s,"quantity":1}' "$PRODUCT_ID" > "$TMPD/cart.json"
  curl -s -X POST "$BASE/cart" -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
    --data @"$TMPD/cart.json" > /dev/null
  TOKENS[$i]=$TOKEN
  ADDRS[$i]=$ADDR
  echo "用户 $UN 准备完毕"
done

# 3. 并发发起 20 个下单请求（每个用户用自己的地址，各自独立的 body 文件避免并发写冲突）
rm -f "$TMPD/conc-"*.json
for i in $(seq 1 $TOTAL_USERS); do
  (
    printf '{"addressId":%s}' "${ADDRS[$i]}" > "$TMPD/conc-body-$i.json"
    curl -s -X POST "$BASE/order" -H "Content-Type: application/json" \
      -H "Authorization: Bearer ${TOKENS[$i]}" \
      --data @"$TMPD/conc-body-$i.json" > "$TMPD/conc-$i.json"
  ) &
done
wait

# 4. 统计结果
SUCCESS=0
FAIL_CNT=0
for i in $(seq 1 $TOTAL_USERS); do
  CODE=$(python -c "import json;print(json.load(open(r'$TMPD/conc-$i.json', encoding='utf-8'))['code'])")
  if [ "$CODE" = "200" ]; then
    SUCCESS=$((SUCCESS + 1))
  else
    FAIL_CNT=$((FAIL_CNT + 1))
  fi
done

# 5. 查询最终库存与订单明细（绕过缓存，直接查库）
FINAL_STOCK=$(docker exec ecommerce-mysql mysql -uroot -proot123456 -N -e \
  "SELECT stock FROM ecommerce.product_info WHERE id=$PRODUCT_ID;" 2>/dev/null)
DB_ORDERS=$(docker exec ecommerce-mysql mysql -uroot -proot123456 -N -e \
  "SELECT COUNT(*) FROM ecommerce.order_master o JOIN ecommerce.order_detail d ON o.order_no=d.order_no WHERE d.product_id=$PRODUCT_ID;" 2>/dev/null)

echo "======================================================"
echo "下单成功：$SUCCESS / $TOTAL_USERS（失败 $FAIL_CNT）"
echo "最终库存：$FINAL_STOCK"
echo "订单明细总数：$DB_ORDERS（应等于成功数）"
echo "======================================================"
if [ "$SUCCESS" = "5" ] && [ "$FINAL_STOCK" = "0" ] && [ "$DB_ORDERS" = "5" ]; then
  echo "结论：PASS —— 并发下无超卖"
  exit 0
else
  echo "结论：FAIL —— 存在超卖或库存异常"
  exit 1
fi
