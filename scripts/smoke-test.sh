#!/usr/bin/env bash
# =============================================================
# 阶段一 全链路冒烟测试：注册→登录→地址→加购→下单→支付→取消→异常场景
# 用法：bash scripts/smoke-test.sh
# 注意：请求体统一写入临时文件再发送（避免 Git Bash 向 Windows 程序
#       传参时 GBK 转码导致中文乱码）
# =============================================================
set -u
# 本机存在 http_proxy 环境变量，curl 访问 localhost 会被代理拦截（502），故逐个请求加 --noproxy '*'
# 请求体文件必须用 Windows 可见路径：本机 curl 是原生 Windows 版，无法解析 MSYS 的 /tmp/xxx
BASE="http://localhost:8080/api"
TMPD=$(cygpath -w /tmp 2>/dev/null || echo "${TEMP:-/tmp}")
UN="user_$(date +%s)"
PASS=0
FAIL=0

# JSON 字段提取，支持数组下标：field "data.0.children"
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

# 断言：check 名称 实际值 期望值(包含匹配)
check() {
  if [[ "$2" == *"$3"* ]]; then
    echo "  PASS  $1"
    PASS=$((PASS + 1))
  else
    echo "  FAIL  $1  (期望包含 [$3]，实际 [$2])"
    FAIL=$((FAIL + 1))
  fi
}

req() { # req METHOD URL [BODY]
  if [ $# -ge 3 ]; then
    printf '%s' "$3" > "$TMPD/req-body.json"
    curl -s --noproxy '*' -X "$1" "$2" -H "Content-Type: application/json" -H "Authorization: Bearer ${TOKEN:-}" --data @"$TMPD/req-body.json"
  else
    curl -s --noproxy '*' -X "$1" "$2" -H "Authorization: Bearer ${TOKEN:-}"
  fi
}

# 管理端请求：发货 / 完成等动作要求 ADMIN 角色
reqAdmin() { # reqAdmin METHOD URL [BODY]
  if [ $# -ge 3 ]; then
    printf '%s' "$3" > "$TMPD/req-admin-body.json"
    curl -s --noproxy '*' -X "$1" "$2" -H "Content-Type: application/json" -H "Authorization: Bearer ${ADMIN_TOKEN:-}" --data @"$TMPD/req-admin-body.json"
  else
    curl -s --noproxy '*' -X "$1" "$2" -H "Authorization: Bearer ${ADMIN_TOKEN:-}"
  fi
}

echo "======================================================"
echo "冒烟测试用户：$UN"
echo "======================================================"

echo "[1] 用户注册"
R=$(req POST "$BASE/user/register" "{\"username\":\"$UN\",\"password\":\"123456\",\"phone\":\"13900001111\"}")
check "注册成功" "$(echo "$R" | field code)" "200"

echo "[2] 用户登录"
R=$(req POST "$BASE/auth/login" "{\"username\":\"$UN\",\"password\":\"123456\"}")
check "登录成功" "$(echo "$R" | field code)" "200"
TOKEN=$(echo "$R" | field data.token)
check "返回token" "$TOKEN" "eyJ"

echo "[3] 查询用户信息"
R=$(req GET "$BASE/user/info")
check "用户名正确" "$(echo "$R" | field data.username)" "$UN"
check "自助注册默认为普通用户" "$(echo "$R" | field data.role)" "USER"

echo "[3b] 管理员登录（发货/完成等管理动作需 ADMIN 角色）"
R=$(req POST "$BASE/auth/login" '{"username":"admin","password":"123456"}')
check "管理员登录成功" "$(echo "$R" | field code)" "200"
ADMIN_TOKEN=$(echo "$R" | field data.token)
check "管理员返回token" "$ADMIN_TOKEN" "eyJ"
check "管理员角色为 ADMIN" "$(echo "$R" | field data.userInfo.role)" "ADMIN"

echo "[4] 新增收货地址（默认）"
R=$(req POST "$BASE/address" "{\"receiverName\":\"张三\",\"receiverPhone\":\"13812345678\",\"address\":\"北京市朝阳区望京街1号\",\"isDefault\":1}")
check "地址1创建" "$(echo "$R" | field code)" "200"
ADDR1=$(echo "$R" | field data)
check "地址1ID非空" "$ADDR1" ""

echo "[5] 新增收货地址（非默认）"
R=$(req POST "$BASE/address" "{\"receiverName\":\"李四\",\"receiverPhone\":\"13700002222\",\"address\":\"上海市浦东新区世纪大道100号\",\"isDefault\":0}")
ADDR2=$(echo "$R" | field data)
check "地址2ID非空" "$ADDR2" ""

echo "[6] 设置默认地址"
R=$(req PUT "$BASE/address/$ADDR2/default")
check "设置默认成功" "$(echo "$R" | field code)" "200"
R=$(req GET "$BASE/address/list")
CNT=$(echo "$R" | python -c "import sys,json;print(len(json.load(sys.stdin)['data']))")
check "地址列表共2条" "$CNT" "2"
DEFAULT_CNT=$(echo "$R" | python -c "import sys,json;print(sum(1 for a in json.load(sys.stdin)['data'] if a['isDefault']==1))")
check "默认地址唯一" "$DEFAULT_CNT" "1"
FIRST_DEFAULT=$(echo "$R" | field data.0.id)
check "默认地址为地址2" "$FIRST_DEFAULT" "$ADDR2"

echo "[7] 分类树查询"
R=$(req GET "$BASE/category/tree")
check "分类树查询" "$(echo "$R" | field code)" "200"
check "一级分类为手机数码" "$(echo "$R" | field data.0.name)" "手机数码"
check "含子分类手机" "$(echo "$R" | field data.0.children.0.name)" "手机"

echo "[8] 商品分页查询（关键词=键盘，仅"限量版客制化键盘"名称命中）"
R=$(req GET "$BASE/product/page?pageNum=1&pageSize=10&keyword=%E9%94%AE%E7%9B%98")
check "关键词命中1条" "$(echo "$R" | field data.total)" "1"

echo "[9] 商品详情（首次，走缓存）"
R=$(req GET "$BASE/product/1")
check "商品详情" "$(echo "$R" | field data.name)" "iPhone"
R=$(req GET "$BASE/product/1")
check "商品详情（二次命中缓存）" "$(echo "$R" | field data.name)" "iPhone"

echo "[10] 商品详情（不存在，空值缓存）"
R=$(req GET "$BASE/product/99999")
check "空值返回null" "$(echo "$R" | field data)" ""

echo "[11] 加入购物车（数量累加）"
req POST "$BASE/cart" "{\"productId\":1,\"quantity\":2}" > /dev/null
req POST "$BASE/cart" "{\"productId\":1,\"quantity\":1}" > /dev/null
req POST "$BASE/cart" "{\"productId\":6,\"quantity\":1}" > /dev/null
R=$(req GET "$BASE/cart/list")
# 注意：雪花 ID 在 JSON 中是字符串（后端为避免 JS 精度丢失而如此设计），故按字符串比较
QTY1=$(echo "$R" | python -c "import sys,json;print([i['quantity'] for i in json.load(sys.stdin)['data'] if str(i['productId'])=='1'][0])")
check "同商品累加为3" "$QTY1" "3"

echo "[12] 创建订单"
R=$(req POST "$BASE/order" "{\"addressId\":$ADDR2}")
check "下单成功" "$(echo "$R" | field code)" "200"
ORDER_ID=$(echo "$R" | field data.id)
ORDER_NO=$(echo "$R" | field data.orderNo)
check "订单号生成" "$ORDER_NO" ""
check "订单金额 27296" "$(echo "$R" | field data.totalAmount)" "27296"
check "收货人李四" "$(echo "$R" | field data.receiverName)" "李四"

echo "[13] 下单后购物车已清空"
R=$(req GET "$BASE/cart/list")
CNT=$(echo "$R" | python -c "import sys,json;print(len(json.load(sys.stdin)['data']))")
check "购物车为空" "$CNT" "0"

echo "[14] 订单详情"
R=$(req GET "$BASE/order/$ORDER_ID")
check "待支付状态" "$(echo "$R" | field data.orderStatus)" "0"
ITEM_CNT=$(echo "$R" | python -c "import sys,json;print(len(json.load(sys.stdin)['data']['items']))")
check "明细条数" "$ITEM_CNT" "2"
check "商品快照名称" "$(echo "$R" | field data.items.0.productName)" "iPhone"

echo "[15] 订单分页列表"
R=$(req GET "$BASE/order/page?pageNum=1&pageSize=10")
check "列表总数" "$(echo "$R" | field data.total)" "1"

echo "[16] 模拟支付"
R=$(req POST "$BASE/order/$ORDER_ID/pay")
check "已支付" "$(echo "$R" | field data.orderStatus)" "1"
check "支付时间填充" "$(echo "$R" | field data.payTime)" "2026"

echo "[17] 权限校验 + 发货 / 完成（需 ADMIN）"
R=$(req POST "$BASE/order/$ORDER_ID/ship")
check "普通用户发货被拒（403）" "$(echo "$R" | field code)" "403"
R=$(reqAdmin POST "$BASE/order/$ORDER_ID/ship")
check "管理员发货成功" "$(echo "$R" | field data.orderStatus)" "2"
R=$(req POST "$BASE/order/$ORDER_ID/complete")
check "普通用户完成被拒（403）" "$(echo "$R" | field code)" "403"
R=$(reqAdmin POST "$BASE/order/$ORDER_ID/complete")
check "管理员完成成功" "$(echo "$R" | field data.orderStatus)" "3"

echo "[17b] 权限校验：普通用户不可写商品/分类"
R=$(req PUT "$BASE/product/1/status?status=0")
check "普通用户改上下架被拒（403）" "$(echo "$R" | field code)" "403"
R=$(reqAdmin PUT "$BASE/product/1/status?status=1")
check "管理员改上下架成功" "$(echo "$R" | field code)" "200"

echo "[18] 取消订单（回补库存）"
req POST "$BASE/cart" "{\"productId\":8,\"quantity\":1}" > /dev/null
R=$(req POST "$BASE/order" "{\"addressId\":$ADDR1}")
ORDER2=$(echo "$R" | field data.id)
R=$(req POST "$BASE/order/$ORDER2/cancel")
check "已取消" "$(echo "$R" | field data.orderStatus)" "4"
R=$(req GET "$BASE/product/8")
check "库存回补为60" "$(echo "$R" | field data.stock)" "60"

echo "[19] 异常场景"
HTTP=$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" "$BASE/cart/list")
check "无token返回401" "$HTTP" "401"
# 白名单精确匹配防回归：以下写接口在修复前曾是匿名可访问（越权漏洞）
HTTP=$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -X POST "$BASE/product" \
  -H "Content-Type: application/json" --data '{}')
check "匿名新增商品返回401" "$HTTP" "401"
HTTP=$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -X PUT "$BASE/product/1" \
  -H "Content-Type: application/json" --data '{}')
check "匿名改商品返回401" "$HTTP" "401"
HTTP=$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -X DELETE "$BASE/product/1")
check "匿名删商品返回401" "$HTTP" "401"
HTTP=$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -X DELETE "$BASE/category/1")
check "匿名删分类返回401" "$HTTP" "401"
HTTP=$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" "$BASE/product/page?pageNum=1&pageSize=1")
check "匿名查商品仍放行" "$HTTP" "200"
R=$(req POST "$BASE/cart" "{\"productId\":11,\"quantity\":1}")
check "下架商品拒加购" "$(echo "$R" | field code)" "3002"
R=$(req POST "$BASE/cart" "{\"productId\":10,\"quantity\":999}")
check "超库存拒加购" "$(echo "$R" | field code)" "3003"
R=$(req POST "$BASE/order" "{\"addressId\":999999}")
check "非法地址下单拒绝" "$(echo "$R" | field code)" "5001"
R=$(req POST "$BASE/order" "{\"addressId\":$ADDR1}")
check "空购物车下单拒绝" "$(echo "$R" | field code)" "4001"
R=$(req POST "$BASE/order/$ORDER_ID/cancel")
check "已完成订单不可取消" "$(echo "$R" | field code)" "6002"
R=$(req POST "$BASE/auth/login" "{\"username\":\"$UN\",\"password\":\"wrong\"}")
check "错误密码拒绝" "$(echo "$R" | field code)" "2001"

echo "======================================================"
echo "结果：PASS=$PASS  FAIL=$FAIL"
echo "======================================================"
[ "$FAIL" -eq 0 ]
