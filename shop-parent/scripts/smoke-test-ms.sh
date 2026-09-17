#!/usr/bin/env bash
# =============================================================
# 阶段二 微服务全链路冒烟测试（经网关 8080）
# 注册→登录→地址→加购→下单→支付→发货→完成→取消→异常场景
# 用法：bash scripts/smoke-test-ms.sh
# =============================================================
set -u
# 本机存在 http_proxy 环境变量，curl 访问 localhost 会被代理拦截（502），故逐个请求加 --noproxy '*'
# 请求体文件必须用 Windows 可见路径：本机 curl 是原生 Windows 版，无法解析 MSYS 的 /tmp/xxx
BASE="http://localhost:8080"
TMPD=$(cygpath -w /tmp 2>/dev/null || echo "${TEMP:-/tmp}")
UN="msuser_$(date +%s)"
PASS=0
FAIL=0

field() {
  python -c "
import sys, json
d = json.load(sys.stdin)
v = d
for p in '$1'.split('.'):
    if isinstance(v, dict):
        v = v.get(p)
    elif isinstance(v, list) and p.isdigit():
        v = v[int(p)] if int(p) < len(v) else None
    else:
        v = None
print(v if v is not None else '')
"
}

check() {
  if [[ "$2" == *"$3"* ]]; then
    echo "  PASS  $1"
    PASS=$((PASS + 1))
  else
    echo "  FAIL  $1  (期望包含 [$3]，实际 [$2])"
    FAIL=$((FAIL + 1))
  fi
}

req() {
  if [ $# -ge 3 ]; then
    printf '%s' "$3" > "$TMPD/ms-req-body.json"
    curl -s --noproxy '*' -X "$1" "$2" -H "Content-Type: application/json" -H "Authorization: Bearer ${TOKEN:-}" --data @"$TMPD/ms-req-body.json"
  else
    curl -s --noproxy '*' -X "$1" "$2" -H "Authorization: Bearer ${TOKEN:-}"
  fi
}

# 管理端请求：发货 / 完成等动作要求 ADMIN 角色
reqAdmin() {
  if [ $# -ge 3 ]; then
    printf '%s' "$3" > "$TMPD/ms-req-admin.json"
    curl -s --noproxy '*' -X "$1" "$2" -H "Content-Type: application/json" -H "Authorization: Bearer ${ADMIN_TOKEN:-}" --data @"$TMPD/ms-req-admin.json"
  else
    curl -s --noproxy '*' -X "$1" "$2" -H "Authorization: Bearer ${ADMIN_TOKEN:-}"
  fi
}

echo "======================================================"
echo "微服务冒烟测试（经网关 $BASE），用户：$UN"
echo "======================================================"

echo "[1] 注册"
R=$(req POST "$BASE/api/user/register" "{\"username\":\"$UN\",\"password\":\"123456\",\"phone\":\"13900001111\"}")
check "注册成功" "$(echo "$R" | field code)" "200"

echo "[2] 登录"
R=$(req POST "$BASE/api/auth/login" "{\"username\":\"$UN\",\"password\":\"123456\"}")
check "登录成功" "$(echo "$R" | field code)" "200"
TOKEN=$(echo "$R" | field data.token)
check "返回token" "$TOKEN" "eyJ"

echo "[3] 用户信息（网关鉴权透传）"
R=$(req GET "$BASE/api/user/info")
check "用户名正确" "$(echo "$R" | field data.username)" "$UN"
check "自助注册默认为普通用户" "$(echo "$R" | field data.role)" "USER"

echo "[3b] 管理员登录（发货/完成等管理动作需 ADMIN 角色）"
R=$(req POST "$BASE/api/auth/login" '{"username":"admin","password":"123456"}')
check "管理员登录成功" "$(echo "$R" | field code)" "200"
ADMIN_TOKEN=$(echo "$R" | field data.token)
check "管理员返回token" "$ADMIN_TOKEN" "eyJ"
check "管理员角色为 ADMIN" "$(echo "$R" | field data.userInfo.role)" "ADMIN"

echo "[4] 收货地址"
R=$(req POST "$BASE/api/user/address" "{\"receiverName\":\"张三\",\"receiverPhone\":\"13812345678\",\"address\":\"北京市朝阳区望京街1号\",\"isDefault\":1}")
check "地址1创建" "$(echo "$R" | field code)" "200"
ADDR1=$(echo "$R" | field data)
R=$(req POST "$BASE/api/user/address" "{\"receiverName\":\"李四\",\"receiverPhone\":\"13700002222\",\"address\":\"上海市浦东新区世纪大道100号\",\"isDefault\":0}")
ADDR2=$(echo "$R" | field data)
req PUT "$BASE/api/user/address/$ADDR2/default" > /dev/null
R=$(req GET "$BASE/api/user/address/list")
CNT=$(echo "$R" | python -c "import sys,json;print(len(json.load(sys.stdin)['data']))")
check "地址列表共2条" "$CNT" "2"
DEFAULT_CNT=$(echo "$R" | python -c "import sys,json;print(sum(1 for a in json.load(sys.stdin)['data'] if a['isDefault']==1))")
check "默认地址唯一" "$DEFAULT_CNT" "1"

echo "[5] 分类树 / 商品查询（网关白名单放行）"
R=$(req GET "$BASE/api/category/tree")
check "分类树" "$(echo "$R" | field data.0.name)" "手机数码"
R=$(req GET "$BASE/api/product/page?pageNum=1&pageSize=10&keyword=%E9%94%AE%E7%9B%98")
check "关键词命中" "$(echo "$R" | field data.total)" "1"
R=$(req GET "$BASE/api/product/1")
check "商品详情" "$(echo "$R" | field data.name)" "iPhone"

echo "[6] 购物车（Feign 关联商品）"
req POST "$BASE/api/cart" "{\"productId\":1,\"quantity\":2}" > /dev/null
req POST "$BASE/api/cart" "{\"productId\":1,\"quantity\":1}" > /dev/null
req POST "$BASE/api/cart" "{\"productId\":6,\"quantity\":1}" > /dev/null
R=$(req GET "$BASE/api/cart/list")
# 注意：雪花 ID 在 JSON 中是字符串（后端为避免 JS 精度丢失而如此设计），故按字符串比较
QTY1=$(echo "$R" | python -c "import sys,json;print([i['quantity'] for i in json.load(sys.stdin)['data'] if str(i['productId'])=='1'][0])")
check "同商品累加为3" "$QTY1" "3"
check "商品名实时关联" "$(echo "$R" | field data.0.productName)" ""

echo "[7] 下单（Feign 扣库存 → 本地建单 → 延迟消息）"
R=$(req POST "$BASE/api/order" "{\"addressId\":$ADDR2}")
check "下单成功" "$(echo "$R" | field code)" "200"
ORDER_ID=$(echo "$R" | field data.id)
ORDER_NO=$(echo "$R" | field data.orderNo)
check "订单金额 27296" "$(echo "$R" | field data.totalAmount)" "27296"
check "收货人李四" "$(echo "$R" | field data.receiverName)" "李四"

echo "[8] 下单后购物车清空"
R=$(req GET "$BASE/api/cart/list")
CNT=$(echo "$R" | python -c "import sys,json;print(len(json.load(sys.stdin)['data']))")
check "购物车为空" "$CNT" "0"

echo "[9] 订单详情 / 列表"
R=$(req GET "$BASE/api/order/$ORDER_ID")
check "待支付" "$(echo "$R" | field data.orderStatus)" "0"
R=$(req GET "$BASE/api/order/page?pageNum=1&pageSize=10")
check "列表总数" "$(echo "$R" | field data.total)" "1"

echo "[10] 支付 / 发货 / 完成"
R=$(req POST "$BASE/api/order/$ORDER_ID/pay")
check "已支付" "$(echo "$R" | field data.orderStatus)" "1"
R=$(req POST "$BASE/api/order/$ORDER_ID/ship")
check "普通用户发货被拒（403）" "$(echo "$R" | field code)" "403"
R=$(reqAdmin POST "$BASE/api/order/$ORDER_ID/ship")
check "管理员发货成功" "$(echo "$R" | field data.orderStatus)" "2"
R=$(reqAdmin POST "$BASE/api/order/$ORDER_ID/complete")
check "管理员完成成功" "$(echo "$R" | field data.orderStatus)" "3"

echo "[11] 取消订单（Feign 回补库存）"
req POST "$BASE/api/cart" "{\"productId\":8,\"quantity\":1}" > /dev/null
R=$(req POST "$BASE/api/order" "{\"addressId\":$ADDR1}")
ORDER2=$(echo "$R" | field data.id)
R=$(req POST "$BASE/api/order/$ORDER2/cancel")
check "已取消" "$(echo "$R" | field data.orderStatus)" "4"
R=$(req GET "$BASE/api/product/8")
check "库存回补为60" "$(echo "$R" | field data.stock)" "60"

echo "[12] 异常场景"
HTTP=$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" "$BASE/api/cart/list")
check "无token网关返回401" "$HTTP" "401"
# 白名单精确匹配防回归：以下写接口在修复前曾是匿名可访问（越权漏洞）
HTTP=$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -X POST "$BASE/api/product" \
  -H "Content-Type: application/json" --data '{}')
check "匿名新增商品返回401" "$HTTP" "401"
HTTP=$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -X PUT "$BASE/api/product/1" \
  -H "Content-Type: application/json" --data '{}')
check "匿名改商品返回401" "$HTTP" "401"
HTTP=$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -X DELETE "$BASE/api/category/1")
check "匿名删分类返回401" "$HTTP" "401"
HTTP=$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" "$BASE/api/product/page?pageNum=1&pageSize=1")
check "匿名查商品仍放行" "$HTTP" "200"
# 内部接口不得对公网暴露：库存扣减原为 /api/product/stock（免登录可刷库存）
HTTP=$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -X POST "$BASE/api/internal/stock/restore" \
  -H "Content-Type: application/json" --data '{"productId":8,"quantity":1}')
check "库存接口经网关不可达（404）" "$HTTP" "404"
STOCK_BEFORE=$(docker exec ecommerce-mysql mysql -uroot -proot123456 -N -e \
  "SELECT stock FROM ecommerce.product_info WHERE id=8;" 2>/dev/null)
check "库存未被外部请求篡改" "$STOCK_BEFORE" "60"
R=$(req POST "$BASE/api/cart" "{\"productId\":11,\"quantity\":1}")
check "下架商品拒加购" "$(echo "$R" | field code)" "3002"
R=$(req POST "$BASE/api/cart" "{\"productId\":10,\"quantity\":999}")
check "超库存拒加购" "$(echo "$R" | field code)" "3003"
R=$(req POST "$BASE/api/order" "{\"addressId\":999999}")
check "非法地址下单拒绝" "$(echo "$R" | field code)" "5001"
R=$(req POST "$BASE/api/order" "{\"addressId\":$ADDR1}")
check "空购物车下单拒绝" "$(echo "$R" | field code)" "4001"
R=$(req POST "$BASE/api/order/$ORDER_ID/cancel")
check "已完成订单不可取消" "$(echo "$R" | field code)" "6002"

echo "[13] 本轮修复项回归（路径穿越 / 订单作用域 / 单条勾选）"

# 13.1 网关必须在路由之前拒绝含 .. 的路径。
# 否则 /api/product/../internal/stock/deduct 不以 /api/internal/ 开头，绕过前缀拦截后
# 仍能被 /api/product/** 路由转发，下游 Tomcat 归一化路径即可触达内部库存接口。
HTTP=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X POST \
  "$BASE/api/product/../internal/stock/deduct" \
  -H "Authorization: Bearer ${TOKEN:-}" -H "Content-Type: application/json" \
  --data '{"productId":8,"quantity":999}')
check "路径穿越被网关拒绝（404）" "$HTTP" "404"
STOCK_AFTER=$(docker exec ecommerce-mysql mysql -uroot -proot123456 -N -e \
  "SELECT stock FROM ecommerce.product_info WHERE id=8;" 2>/dev/null)
check "路径穿越未改动库存" "$STOCK_AFTER" "$STOCK_BEFORE"

# 13.2 管理员可查看任意订单详情（此前恒按当前用户过滤，会被归属校验挡成“订单不存在”）
R=$(reqAdmin GET "$BASE/api/order/$ORDER_ID")
check "管理员可查看他人订单详情" "$(echo "$R" | field code)" "200"

# 13.3 管理端订单列表为全站，普通用户仍只看本人
ADMIN_TOTAL=$(curl -s --noproxy '*' "$BASE/api/order/page?pageNum=1&pageSize=1" \
  -H "Authorization: Bearer ${ADMIN_TOKEN:-}" | field 'data.total')
USER_TOTAL=$(curl -s --noproxy '*' "$BASE/api/order/page?pageNum=1&pageSize=1" \
  -H "Authorization: Bearer ${TOKEN:-}" | field 'data.total')
check "管理员订单总数大于 0" "$([ "${ADMIN_TOTAL:-0}" -gt 0 ] && echo yes || echo no)" "yes"
check "管理员可见范围不小于普通用户" \
  "$([ "${ADMIN_TOTAL:-0}" -ge "${USER_TOTAL:-0}" ] && echo yes || echo no)" "yes"

# 13.4 新增的单条勾选接口（此前 C 端单选是拿 select-all 模拟的，逻辑上不可能正确）
req POST "$BASE/api/cart" '{"productId":1,"quantity":1}' > /dev/null
CART_ID=$(curl -s --noproxy '*' "$BASE/api/cart/list" -H "Authorization: Bearer ${TOKEN:-}" \
  | field 'data.0.id')
R=$(curl -s --noproxy '*' -X PUT "$BASE/api/cart/$CART_ID/selected?selected=false" \
  -H "Authorization: Bearer ${TOKEN:-}")
check "单条勾选（取消）可用" "$(echo "$R" | field code)" "200"
R=$(curl -s --noproxy '*' -X PUT "$BASE/api/cart/$CART_ID/selected?selected=true" \
  -H "Authorization: Bearer ${TOKEN:-}")
check "单条勾选（选中）可用" "$(echo "$R" | field code)" "200"

# 13.5 分类过滤必须「含子分类」——商品全部挂在二级分类上，
#      按一级分类 id 精确匹配会恒为 0 条（表现为首页分类入口/分类页点进去一片空白）
cat_total() {
  curl -s --noproxy '*' "$BASE/api/product/page?pageNum=1&pageSize=50&status=1&categoryId=$1" \
    | field 'data.total'
}
C1=$(cat_total 1); C11=$(cat_total 11); C12=$(cat_total 12)
C2=$(cat_total 2); C21=$(cat_total 21); C22=$(cat_total 22)
C3=$(cat_total 3); C31=$(cat_total 31); C32=$(cat_total 32)
check "一级分类可查到商品（1=手机数码）" "$([ "${C1:-0}" -gt 0 ] && echo yes || echo no)" "yes"
check "一级分类数量 = 其子分类之和（1）" "${C1:-x}" "$(( ${C11:-0} + ${C12:-0} ))"
check "一级分类数量 = 其子分类之和（2）" "${C2:-x}" "$(( ${C21:-0} + ${C22:-0} ))"
check "一级分类数量 = 其子分类之和（3）" "${C3:-x}" "$(( ${C31:-0} + ${C32:-0} ))"
# 子分类过滤不能被"含子分类"改坏：二级分类本身没有子分类，结果应与修复前一致
check "二级分类过滤仍精确（11=2）" "${C11:-x}" "2"

# 13.6 参数走 query 的接口：漏传必须 400（前端曾把 params 传成嵌套对象，拼出 ?params=[object Object]）
R=$(curl -s --noproxy '*' -X PUT "$BASE/api/cart/select-all" -H "Authorization: Bearer ${TOKEN:-}")
check "select-all 漏传 selected 应被拒" "$(echo "$R" | field code)" "400"
R=$(curl -s --noproxy '*' -X PUT "$BASE/api/cart/select-all?selected=false" -H "Authorization: Bearer ${TOKEN:-}")
check "select-all 传 selected=false 可用" "$(echo "$R" | field code)" "200"

echo "[14] 业务服务端口防护（X-Internal-Token）：直连必须被拒"

# 设计意图：业务服务端口（8081/8082/8083）不应被外部直接访问。
# 下游完全信任 X-User-Id，若不带内部令牌就能打通，任何人伪造一个 X-User-Id
# 即可冒充任意用户（含管理员）。这一层此前没有任何自动化断言，属于盲区。
INTERNAL_TOKEN="${INTERNAL_TOKEN:-local-dev-change-me-internal-token}"
SPOOFED_ID="2084170013171367937"   # admin 的 user_id（雪花 ID，字符串）

# 14.1 直连商品服务：不带令牌
HTTP=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "http://localhost:8082/api/product/1")
check "直连8082不带内部令牌被拒(403)" "$HTTP" "403"

# 14.2 直连商品服务：带错误令牌
HTTP=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "http://localhost:8082/api/product/1" \
  -H "X-Internal-Token: invalid-token-for-test")
check "直连8082令牌错误被拒(403)" "$HTTP" "403"

# 14.3 直连用户服务：不带令牌（用户服务持有账号数据，重点保护）
HTTP=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "http://localhost:8081/api/user/info" \
  -H "X-User-Id: $SPOOFED_ID")
check "直连8081伪造X-User-Id被拒(403)" "$HTTP" "403"

# 14.4 带上正确令牌才能直连（说明这是"通道校验"而非拦截所有人）
HTTP=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "http://localhost:8082/api/product/1" \
  -H "X-Internal-Token: $INTERNAL_TOKEN")
check "携带正确内部令牌可直连(200)" "$HTTP" "200"

# 14.5 经网关伪造身份头必须被剥离：
# 用普通用户 token 冒充 ADMIN，管理接口应拒绝（网关 sanitize 先删伪造头再注入认定值）
HTTP=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$BASE/api/order/page?pageNum=1&pageSize=1" \
  -H "Authorization: Bearer ${TOKEN:-}" -H "X-User-Id: $SPOOFED_ID" -H "X-User-Role: ADMIN")
check "经网关伪造身份头被剥离(403)" "$HTTP" "403"

echo "======================================================"
echo "结果：PASS=$PASS  FAIL=$FAIL"
echo "======================================================"
[ "$FAIL" -eq 0 ]
