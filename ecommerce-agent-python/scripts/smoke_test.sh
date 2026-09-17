#!/usr/bin/env bash
# 全链路冒烟测试：会话 → 对话 → 文档上传 → 向量检索 → 工具查询 → 删除清理
# 注意：中文 body 一律写文件后 --data @file（规避 Git Bash GBK 传参问题）
#
# 重要：**统一经电商网关 8080**（网关有 /api/agent/** 路由转发到 Python :8000）。
# 会话类接口要求登录，身份由网关按 token 注入 X-User-Id —— 脚本不再传 user_id。
set -e
BASE=${BASE:-http://localhost:8080}
DIRECT=${DIRECT:-http://localhost:8000}   # 仅用于验证「直连已拿不到身份」
ADMIN_USER=${ADMIN_USER:-admin}
ADMIN_PASS=${ADMIN_PASS:-123456}
TMP=scripts/tmp
mkdir -p "$TMP"
PASS=0; FAIL=0

ok()   { PASS=$((PASS+1)); echo "  ✓ $1"; }
fail() { FAIL=$((FAIL+1)); echo "  ✗ $1"; }
check() { # $1 描述 $2 实际 $3 期望子串
  if echo "$2" | grep -q "$3"; then ok "$1"; else fail "$1 (期望含 $3，实际: ${2:0:200})"; fi
}

echo "== 0. 取管理员 token（经网关登录）=="
printf '{"username":"%s","password":"%s"}' "$ADMIN_USER" "$ADMIN_PASS" > "$TMP/login.json"
r=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' --data @"$TMP/login.json")
TOKEN=$(echo "$r" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p' | head -1)
check "登录取得 token" "$r" '"token"'
[ -n "$TOKEN" ] || { echo "  无法登录，后续用例无法执行"; exit 1; }
AUTH="Authorization: Bearer $TOKEN"

echo "== 1. 健康检查（白名单，匿名可查） =="
r=$(curl -s "$BASE/api/agent/health")
check "health 返回 code=200" "$r" '"code":200'

echo "== 1.5 鉴权边界（本轮修复重点：Agent 已收回网关）=="
# 会话接口必须登录：不带 token 打网关应被拒
r=$(curl -s "$BASE/api/agent/session/list")
check "会话列表不带 token 被拒(401)" "$r" '"code":401'
# 直连 8000 并自报 user_id 已无效：没有网关注入的身份头一律 401
r=$(curl -s "$DIRECT/api/agent/session/list?user_id=1")
check "直连 8000 自报 user_id 无效(401)" "$r" '"code":401'
r=$(curl -s -X DELETE "$DIRECT/api/agent/session/delete/1?user_id=1")
check "直连 8000 删除他人会话无效(401)" "$r" '"code":401'

# 知识库/工具配置属管理动作：普通用户必须 403（此前只要求登录，任何用户都能删库停工具）
USER_NAME="smokeuser_$(date +%s)"
printf '{"username":"%s","password":"123456","phone":"13900001111"}' "$USER_NAME" > "$TMP/reg.json"
curl -s -X POST "$BASE/api/user/register" -H 'Content-Type: application/json' --data @"$TMP/reg.json" > /dev/null
printf '{"username":"%s","password":"123456"}' "$USER_NAME" > "$TMP/ulogin.json"
r=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' --data @"$TMP/ulogin.json")
UTOKEN=$(echo "$r" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p' | head -1)
if [ -n "$UTOKEN" ]; then
  UAUTH="Authorization: Bearer $UTOKEN"
  r=$(curl -s "$BASE/api/agent/knowledge/list" -H "$UAUTH")
  check "普通用户访问知识库被拒(403)" "$r" '"code":403'
  r=$(curl -s "$BASE/api/agent/tool/list" -H "$UAUTH")
  check "普通用户访问工具配置被拒(403)" "$r" '"code":403'
else
  fail "普通用户登录失败，无法验证 403 边界"
fi

echo "== 2. 会话管理（登录态）=="
r=$(curl -s "$BASE/api/agent/session/list" -H "$AUTH")
check "会话列表" "$r" '"code":200'
printf '{"title":"smoke"}' > "$TMP/session.json"
r=$(curl -s -X POST "$BASE/api/agent/session/create" -H "$AUTH" -H 'Content-Type: application/json' --data @"$TMP/session.json")
# 雪花 ID 在 JSON 中是字符串（后端为避免 JS 精度丢失如此设计），故正则需兼容两侧引号
SID=$(echo "$r" | sed -n 's/.*"id":"\{0,1\}\([0-9]*\)"\{0,1\}.*/\1/p' | head -1)
check "创建会话返回 id" "$r" '"id":'
echo "  SID=$SID"

echo "== 3. 对话（DeepSeek 真实回答）=="
printf '{"session_id":%s,"content":"你好，请简单介绍你自己"}' "$SID" > "$TMP/chat.json"
r=$(curl -s -X POST "$BASE/api/agent/chat" -H "$AUTH" -H 'Content-Type: application/json' --data @"$TMP/chat.json")
check "chat 返回回答" "$r" '"answer"'
# 游客（不带 token）也能问：可选鉴权，不应 401
printf '{"content":"你好"}' > "$TMP/chat_guest.json"
r=$(curl -s -X POST "$BASE/api/agent/chat" -H 'Content-Type: application/json' --data @"$TMP/chat_guest.json")
check "游客不带 token 也能对话（可选鉴权）" "$r" '"answer"'
r=$(curl -s "$BASE/api/agent/session/$SID/messages" -H "$AUTH")
check "消息历史 2 条" "$r" '"role"'

echo "== 4. 知识库（上传 → 向量化 → RAG 检索）=="
mkdir -p "$TMP/docs"
# 文件名用 ASCII：Git Bash 向 Windows curl 传中文文件名会被转 GBK（README 已知坑）
cat > "$TMP/docs/aftersale-policy.md" <<'EOF'
# 轻量电商售后政策

## 七天无理由退货
自签收之日起 7 天内，商品保持完好、不影响二次销售，可申请无理由退货。
运费规则：因质量问题退货运费由商家承担；非质量问题由买家承担。

## 换货
商品出现质量问题（如破损、故障、描述不符）可在 15 天内申请换货，换货产生的来回运费均由商家承担。

## 保修
电子产品整机保修 1 年，配件保修 6 个月。保修期内非人为损坏免费维修。

## 退款时效
退货商品验收通过后，退款将在 1-3 个工作日内原路返回支付账户。

## 售后流程
订单页申请售后 → 填写原因并上传凭证 → 商家 24 小时内审核 → 审核通过寄回商品 → 验收退款。

## 特殊赔付规则
政策编号 SF-2026-0916 规定：大件家电（空调、冰箱、洗衣机）退货，无论质量问题与否，往返运费均由商家承担。
EOF
r=$(curl -s -X POST "$BASE/api/agent/knowledge/upload" -H "$AUTH" -F "file=@$TMP/docs/aftersale-policy.md")
check "文档上传返回 doc_id" "$r" '"doc_id"'
DOC_ID=$(echo "$r" | sed -n 's/.*"doc_id":"\{0,1\}\([0-9]*\)"\{0,1\}.*/\1/p' | head -1)
echo "  DOC_ID=$DOC_ID（等待向量化）"
for i in 1 2 3 4 5 6 7 8 9 10; do
  sleep 3
  r=$(curl -s "$BASE/api/agent/knowledge/list?page=1&page_size=10" -H "$AUTH")
  if echo "$r" | grep -q '"status":2'; then break; fi
done
check "文档状态=已生效(2)" "$r" '"status":2'
r=$(curl -s "$BASE/api/agent/knowledge/list?page=1&page_size=10" -H "$AUTH")
check "chunk_count>0" "$r" '"chunk_count":[1-9]'
printf '{"session_id":%s,"content":"七天内无理由退货的运费怎么算？"}' "$SID" > "$TMP/rag.json"
r=$(curl -s -X POST "$BASE/api/agent/chat" -H "$AUTH" -H 'Content-Type: application/json' --data @"$TMP/rag.json")
check "RAG 回答命中知识库" "$r" '运费'

# 混合检索专项：编号类关键词（SF-2026-0916）语义上与「运费怎么算」无关，
# 纯向量路常漏召，BM25 关键词路能精确命中 —— 这条用例若失败即说明关键词路没生效。
printf '{"session_id":%s,"content":"政策编号 SF-2026-0916 规定了什么？"}' "$SID" > "$TMP/rag_code.json"
r=$(curl -s -X POST "$BASE/api/agent/chat" -H "$AUTH" -H 'Content-Type: application/json' --data @"$TMP/rag_code.json")
check "编号类精确关键词被召回（混合检索生效）" "$r" 'SF-2026-0916'

echo "== 5. 工具调用（商品/订单/售后）=="
printf '{"session_id":%s,"content":"查一下商品库存，名称包含 手机 的"}' "$SID" > "$TMP/tool_product.json"
r=$(curl -s -X POST "$BASE/api/agent/chat" -H "$AUTH" -H 'Content-Type: application/json' --data @"$TMP/tool_product.json")
check "商品查询工具触发" "$r" '商品'
r=$(curl -s "$BASE/api/agent/tool/list" -H "$AUTH")
check "工具列表 3 条" "$r" 'query_after_sale_policy'
printf '{"status":0}' > "$TMP/tool_off.json"
TOOL_ID=$(echo "$r" | sed -n 's/.*"id":"\{0,1\}\([0-9]*\)"\{0,1\},"tool_name":"query_product_stock".*/\1/p' | head -1)
[ -z "$TOOL_ID" ] && TOOL_ID=$(echo "$r" | sed -n 's/.*"id":"\{0,1\}\([0-9]*\)"\{0,1\},"tool_name":"query_product_stock".*/\1/p' | head -1)
echo "  TOOL_ID=$TOOL_ID"

echo "== 6. 清理 =="
r=$(curl -s -X DELETE "$BASE/api/agent/knowledge/delete/$DOC_ID" -H "$AUTH")
check "删除文档" "$r" '"code":200'
r=$(curl -s -X DELETE "$BASE/api/agent/session/delete/$SID" -H "$AUTH")
check "删除会话" "$r" '"code":200'

echo
echo "结果: $PASS 通过 / $FAIL 失败"
[ "$FAIL" -eq 0 ] || exit 1
