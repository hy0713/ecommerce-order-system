<template>
  <view class="agent-page" :style="{ height: pageHeight }">
    <!-- 未登录提示：Agent 本身可用，但查询订单等鉴权工具需要登录 -->
    <view class="login-tip" v-if="!isLogin" @click="goLogin">
      <u-icon name="info-circle" color="#b45309" size="15"></u-icon>
      <text>当前为游客模式，登录后可查询你的订单</text>
      <text class="tip-link">去登录 ></text>
    </view>

    <!-- 会话工具条 -->
    <view class="toolbar">
      <text class="session-title">{{ sessionTitle }}</text>
      <view class="new-btn" @click="newSession">
        <u-icon name="plus" color="#2563eb" size="14"></u-icon>
        <text>新对话</text>
      </view>
    </view>

    <!-- 消息区 -->
    <scroll-view
      class="msg-box"
      scroll-y
      :scroll-into-view="scrollTarget"
      :scroll-with-animation="true"
    >
      <!-- 空态：欢迎语 + 快捷提问（不直接堆一堆空白，给可点的示例） -->
      <view class="welcome" v-if="!messages.length && !sending">
        <view class="welcome-avatar">AI</view>
        <view class="welcome-title">智能客服</view>
        <view class="welcome-sub">基于 RAG 知识库 + 工具调用，可以问我商品库存、订单状态与售后政策</view>
        <view class="quick-list">
          <view class="quick" v-for="(q, i) in quickQuestions" :key="i" @click="askQuick(q)">{{ q }}</view>
        </view>
      </view>

      <view class="msg" v-for="(m, i) in messages" :key="i" :class="m.role">
        <view class="avatar" :class="m.role">{{ m.role === 'user' ? '我' : 'AI' }}</view>
        <view class="bubble-wrap">
          <view class="bubble">
            <text class="bubble-text">{{ pretty(m.content) }}</text>
          </view>
          <view class="tools" v-if="m.tools && m.tools.length">
            <text class="tool-tag" v-for="(t, j) in m.tools" :key="j">已调用工具 · {{ t }}</text>
          </view>
        </view>
      </view>

      <!-- 等待回答 -->
      <view class="msg assistant" v-if="sending">
        <view class="avatar assistant">AI</view>
        <view class="bubble-wrap">
          <view class="bubble typing">
            <text class="dot">●</text><text class="dot">●</text><text class="dot">●</text>
          </view>
        </view>
      </view>

      <view id="msg-bottom" class="msg-bottom"></view>
    </scroll-view>

    <!-- 输入栏 -->
    <view class="input-bar">
      <input
        class="input"
        v-model="input"
        type="text"
        confirm-type="send"
        placeholder="请输入你的问题，如：查一下 iPhone 的库存"
        placeholder-class="ph"
        @confirm="send"
      />
      <view class="send-btn" :class="{ disabled: !canSend }" @click="send">发送</view>
    </view>
  </view>
</template>

<script setup>
import { computed, nextTick, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { storeToRefs } from 'pinia'
import agent from '../../utils/agent'
import { useUserStore } from '../../store/user'

const userStore = useUserStore()
const { isLogin } = storeToRefs(userStore)

const quickQuestions = [
  '查一下 iPhone 的库存',
  '有哪些手机推荐',
  '我的订单到哪了',
  '你们的售后政策是什么'
]

const messages = ref([])
const input = ref('')
const sending = ref(false)
const scrollTarget = ref('')
const sessionId = ref(null)
const sessionTitle = ref('智能客服')

// 页面可用高度：小程序里 windowHeight 已扣除 tabBar，用它做 flex 高度最稳
const pageHeight = (() => {
  try {
    const sys = uni.getSystemInfoSync()
    return (sys.windowHeight || 0) + 'px'
  } catch (e) {
    return '100vh'
  }
})()

// 身份不再由前端上报：网关注入 X-User-Id（会话接口要求登录，对话接口游客可用）
const canSend = computed(() => !!input.value.trim() && !sending.value)

// 记录上次初始化用的身份；登录态变化时要重新加载对应会话
let initedFor = null

onShow(async () => {
  const key = isLogin.value ? 'login' : 'guest'
  if (initedFor === key) return
  initedFor = key
  if (!isLogin.value) {
    // 游客：会话接口需要登录，直接进欢迎态提问（知识类问题仍可用）
    messages.value = []
    sessionId.value = null
    return
  }
  await loadRecent()
})

// 延续最近一个会话（切 tab 回来能看到上次聊到哪），没有则留空等首次提问自动建会话
async function loadRecent() {
  try {
    const list = await agent.get('/session/list')
    const first = (list || [])[0]
    if (!first) {
      messages.value = []
      sessionId.value = null
      return
    }
    sessionId.value = first.id
    sessionTitle.value = first.title || '智能客服'
    const history = await agent.get(`/session/${first.id}/messages`)
    messages.value = (history || []).map((m) => ({
      role: m.role,
      content: m.content,
      tools: parseTools(m.tool_calls)
    }))
    scrollToBottom()
  } catch (e) { /* 已提示，页面仍可作为新会话使用 */ }
}

function newSession() {
  sessionId.value = null
  sessionTitle.value = '智能客服'
  messages.value = []
  input.value = ''
}

function goLogin() {
  uni.navigateTo({ url: '/pages/login/login' })
}

function askQuick(q) {
  input.value = q
  send()
}

async function send() {
  if (!canSend.value) return
  const content = input.value.trim()
  input.value = ''
  messages.value.push({ role: 'user', content, tools: [] })
  sending.value = true
  scrollToBottom()
  try {
    // session_id 为空时后端会自动创建会话，省一次交互；
    // 身份由网关按 token 注入，前端不再传 user_id
    const data = await agent.post('/chat', {
      session_id: sessionId.value,
      content,
      ecom_token: uni.getStorageSync('token') || null
    })
    if (data.session_id) sessionId.value = data.session_id
    messages.value.push({
      role: 'assistant',
      content: data.answer,
      tools: parseTools(data.tool_calls)
    })
  } catch (e) {
    // 失败也要给用户一条可见反馈，否则「点了发送没反应」
    messages.value.push({
      role: 'assistant',
      content: '抱歉，智能客服暂时无法回答，请稍后重试。',
      tools: []
    })
  } finally {
    sending.value = false
    scrollToBottom()
  }
}

function scrollToBottom() {
  nextTick(() => {
    scrollTarget.value = ''
    nextTick(() => { scrollTarget.value = 'msg-bottom' })
  })
}

// 实时响应里 tool_calls 是数组；历史消息里是 JSON 字符串，统一成工具名数组
function parseTools(raw) {
  if (!raw) return []
  let arr = raw
  if (typeof raw === 'string') {
    try { arr = JSON.parse(raw) } catch (e) { return [] }
  }
  if (!Array.isArray(arr)) return []
  return arr.map((t) => (typeof t === 'string' ? t : t && t.tool)).filter(Boolean)
}

/**
 * 轻量 Markdown 降噪：小程序没有 markdown 渲染器（web 端也是纯文本展示），
 * 但模型回答里常带 **加粗** 与表格，原样显示会满屏 `**` 和 `|`。
 * 这里只做无损可读化，不引入解析器。
 */
function pretty(text) {
  return String(text == null ? '' : text)
    .replace(/\*\*/g, '')
    .replace(/^\s*\|[\s\-:|]+\|\s*$/gm, '')
    .replace(/\|/g, ' · ')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}
</script>

<style lang="scss" scoped>
.agent-page {
  display: flex;
  flex-direction: column;
  background: $bg-page;
}
.login-tip {
  display: flex;
  align-items: center;
  gap: 10rpx;
  padding: 16rpx 24rpx;
  background: #fef3c7;
  font-size: 24rpx;
  color: #b45309;
}
.tip-link {
  margin-left: auto;
  color: $brand;
  font-weight: 600;
}
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18rpx 24rpx;
  background: $bg-card;
  border-bottom: 1rpx solid $border-line;
}
.session-title {
  font-size: 26rpx;
  color: $text-2;
}
.new-btn {
  display: flex;
  align-items: center;
  gap: 6rpx;
  font-size: 26rpx;
  color: $brand;
}

.msg-box {
  flex: 1;
  min-height: 0;
  padding: $gap;
}

.welcome {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 60rpx 20rpx 20rpx;
}
.welcome-avatar {
  width: 110rpx;
  height: 110rpx;
  border-radius: 32rpx;
  background: $brand-grad;
  color: #fff;
  font-size: 36rpx;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 24rpx;
  box-shadow: $shadow-brand;
}
.welcome-title {
  font-size: 34rpx;
  font-weight: 600;
  color: $text-1;
  margin-bottom: 12rpx;
}
.welcome-sub {
  font-size: 24rpx;
  color: $text-3;
  text-align: center;
  line-height: 1.6;
  padding: 0 40rpx;
}
.quick-list {
  width: 100%;
  margin-top: 40rpx;
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}
.quick {
  background: $bg-card;
  border-radius: $radius;
  padding: 24rpx 28rpx;
  font-size: 27rpx;
  color: $text-1;
  box-shadow: $shadow-card;
}

.msg {
  display: flex;
  gap: 16rpx;
  margin-bottom: 28rpx;
}
.msg.user {
  flex-direction: row-reverse;
}
.avatar {
  width: 64rpx;
  height: 64rpx;
  border-radius: 20rpx;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24rpx;
  font-weight: 600;
  color: #fff;
}
.avatar.user {
  background: #64748b;
}
.avatar.assistant {
  background: $brand-grad;
}
.bubble-wrap {
  max-width: 76%;
  min-width: 0;
}
.msg.user .bubble-wrap {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}
.bubble {
  background: $bg-card;
  border-radius: 20rpx;
  padding: 22rpx 26rpx;
  box-shadow: $shadow-card;
}
.msg.user .bubble {
  background: $brand-grad;
}
.bubble-text {
  font-size: 27rpx;
  line-height: 1.7;
  color: $text-1;
  /* 保留模型回答里的换行与缩进 */
  white-space: pre-wrap;
  word-break: break-word;
}
.msg.user .bubble-text {
  color: #fff;
}
.bubble.typing {
  display: flex;
  gap: 8rpx;
  align-items: center;
}
.dot {
  font-size: 20rpx;
  color: $text-3;
}
.tools {
  display: flex;
  flex-wrap: wrap;
  gap: 10rpx;
  margin-top: 12rpx;
}
.tool-tag {
  font-size: 20rpx;
  color: $brand;
  background: rgba(37, 99, 235, 0.08);
  border-radius: $radius-pill;
  padding: 6rpx 16rpx;
}
.msg-bottom {
  height: 1rpx;
}

.input-bar {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 16rpx $gap calc(16rpx + env(safe-area-inset-bottom));
  background: $bg-card;
  border-top: 1rpx solid $border-line;
}
.input {
  flex: 1;
  min-width: 0;
  background: $bg-muted;
  border-radius: $radius-pill;
  padding: 18rpx 26rpx;
  font-size: 27rpx;
  color: $text-1;
}
.ph {
  color: $text-3;
  font-size: 26rpx;
}
.send-btn {
  flex-shrink: 0;
  padding: 18rpx 34rpx;
  border-radius: $radius-pill;
  background: $brand-grad;
  color: #fff;
  font-size: 27rpx;
  box-shadow: $shadow-brand;
}
.send-btn.disabled {
  opacity: 0.45;
  box-shadow: none;
}
</style>
