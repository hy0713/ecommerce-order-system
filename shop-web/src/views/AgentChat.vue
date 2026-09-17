<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">智能客服</h2>
        <p class="page-desc">基于 DeepSeek + RAG，可查商品库存、订单，并回答知识库内容</p>
      </div>
    </div>

    <div class="card chat-shell">
      <!-- 左侧：会话列表 -->
      <aside class="sess-side">
        <div class="sess-actions">
          <el-button type="primary" class="sess-new" :icon="Plus" @click="handleCreate">新建会话</el-button>
          <el-button :icon="Collection" @click="openDocs">知识库</el-button>
        </div>
        <div class="sess-list">
          <div
            v-for="s in sessions" :key="s.id"
            class="sess-item" :class="{ active: s.id === currentId }"
            @click="handleSwitch(s.id)"
          >
            <el-icon class="sess-ico"><ChatDotRound /></el-icon>
            <div class="sess-title" :title="s.title">{{ s.title }}</div>
            <el-icon class="sess-del" @click.stop="handleDelete(s)"><Delete /></el-icon>
          </div>
          <el-empty v-if="!sessions.length" description="暂无会话" :image-size="60" />
        </div>
      </aside>

      <!-- 右侧：聊天窗口 -->
      <section class="chat-main">
        <div class="chat-head">
          <div class="chat-title">{{ currentTitle }}</div>
          <div class="chat-sub">AI 助手 · 可查询商品库存 / 订单状态 / 售后政策</div>
        </div>

        <div ref="msgBox" class="msg-box">
          <div v-if="!messages.length && !loading" class="msg-empty">
            <el-icon :size="34"><ChatLineRound /></el-icon>
            <p>开始提问吧，例如「查一下 iPhone 的库存」</p>
          </div>
          <div v-for="(m, i) in messages" :key="i" class="msg-row" :class="m.role">
            <div class="avatar" :class="m.role">{{ m.role === 'user' ? userInitial : 'AI' }}</div>
            <div class="bubble" :class="m.role">{{ m.content }}</div>
          </div>
          <div v-if="loading" class="msg-row assistant">
            <div class="avatar assistant">AI</div>
            <div class="bubble assistant typing"><span class="tdot"></span><span class="tdot"></span><span class="tdot"></span></div>
          </div>
        </div>

        <div class="chat-input">
          <el-input
            v-model="input" size="large"
            placeholder="输入问题，例如：查一下 iPhone 的库存 / 七天无理由退货怎么算运费 / 帮我查订单状态"
            @keyup.enter="handleSend"
          />
          <el-button type="primary" size="large" :loading="loading" :icon="Promotion" @click="handleSend">发送</el-button>
        </div>
      </section>
    </div>

    <!-- 知识库管理 -->
    <el-dialog v-model="docVisible" title="知识库" width="720px">
      <div class="kb-head">
        <input ref="fileInput" type="file" accept=".pdf,.docx,.txt,.md" style="display: none" @change="handleUpload" />
        <el-button type="primary" :icon="UploadFilled" @click="fileInput.click()">上传文档</el-button>
        <span class="kb-tip">支持 PDF / DOCX / TXT / MD，上传后自动向量化，客服即可基于文档回答</span>
      </div>
      <el-table :data="docs" size="small" v-loading="docsLoading">
        <el-table-column prop="title" label="文件名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="doc_type" label="类型" width="80">
          <template #default="{ row }"><el-tag size="small" effect="plain">{{ (row.doc_type || '').toUpperCase() }}</el-tag></template>
        </el-table-column>
        <el-table-column label="向量化状态" width="110">
          <template #default="{ row }">
            <span class="dot" :class="row.status === 2 ? 'c-green' : row.status === 3 ? 'c-red' : 'c-orange'">
              {{ row.status_desc }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="chunk_count" label="分块数" width="90">
          <template #default="{ row }"><span class="num">{{ row.chunk_count }}</span></template>
        </el-table-column>
        <el-table-column prop="create_time" label="创建时间" width="160">
          <template #default="{ row }"><span class="num">{{ (row.create_time || '').replace('T', ' ').slice(0, 19) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="70">
          <template #default="{ row }">
            <el-button link type="danger" size="small" @click="handleDocDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无文档" :image-size="60" /></template>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { ChatDotRound, ChatLineRound, Collection, Delete, Plus, Promotion, UploadFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '../store/user'
import {
  agentChat, agentKnowledgeDelete, agentKnowledgeList, agentKnowledgeUpload,
  agentSessionCreate, agentSessionDelete, agentSessionList, agentSessionMessages
} from '../api/agent'

const userStore = useUserStore()
// 身份（user_id）不再由前端传给 Agent：网关按 token 校验后注入 X-User-Id，
// Agent 侧只信任该头，避免调用方自报身份读取他人会话。
const ecomToken = localStorage.getItem('token')

const sessions = ref([])
const currentId = ref(null)
const messages = ref([])
const input = ref('')
const loading = ref(false)
const msgBox = ref(null)
const docVisible = ref(false)
const docsLoading = ref(false)
const docs = ref([])
const fileInput = ref(null)

const userInitial = computed(() => (userStore.userInfo?.username || '?').slice(0, 1).toUpperCase())
const currentTitle = computed(() => sessions.value.find((s) => s.id === currentId.value)?.title || '智能客服')

const scrollBottom = async () => {
  await nextTick()
  msgBox.value?.scrollTo({ top: msgBox.value.scrollHeight })
}

const loadSessions = async () => {
  const res = await agentSessionList()
  sessions.value = res.data || []
  if (!currentId.value && sessions.value.length) await handleSwitch(sessions.value[0].id)
}

const handleCreate = async () => {
  const res = await agentSessionCreate()
  await loadSessions()
  await handleSwitch(res.data.id)
}

const handleSwitch = async (id) => {
  currentId.value = id
  const res = await agentSessionMessages(id)
  messages.value = res.data || []
  await scrollBottom()
}

const handleDelete = async (s) => {
  await ElMessageBox.confirm(`确定删除会话「${s.title}」？`, '提示', { type: 'warning' })
  await agentSessionDelete(s.id)
  if (currentId.value === s.id) {
    currentId.value = null
    messages.value = []
  }
  await loadSessions()
}

const handleSend = async () => {
  const content = input.value.trim()
  if (!content || loading.value) return
  if (!currentId.value) await handleCreate()
  input.value = ''
  messages.value.push({ role: 'user', content })
  loading.value = true
  try {
    const res = await agentChat({ session_id: currentId.value, content, ecom_token: ecomToken })
    messages.value.push({ role: 'assistant', content: res.data.answer })
    await loadSessions()
    await scrollBottom()
  } finally {
    loading.value = false
  }
}

const loadDocs = async () => {
  docsLoading.value = true
  try {
    const res = await agentKnowledgeList({ page_num: 1, page_size: 50 })
    docs.value = res.data?.records || []
  } finally {
    docsLoading.value = false
  }
}

const openDocs = async () => {
  docVisible.value = true
  await loadDocs()
}

const handleUpload = async (e) => {
  const file = e.target.files?.[0]
  if (!file) return
  const fd = new FormData()
  fd.append('file', file)
  const res = await agentKnowledgeUpload(fd)
  ElMessage.success(`上传成功（文档ID：${res.data.doc_id}），向量化完成后即可生效`)
  await loadDocs()
  e.target.value = ''
}

const handleDocDelete = async (row) => {
  await ElMessageBox.confirm(`确定删除文档「${row.title}」？`, '提示', { type: 'warning' })
  await agentKnowledgeDelete(row.id)
  ElMessage.success('已删除')
  await loadDocs()
}

onMounted(async () => {
  await loadSessions()
  await loadDocs()
})
</script>

<style scoped>
.chat-shell { display: flex; height: calc(100vh - 200px); min-height: 520px; overflow: hidden; }

/* 左侧会话 */
.sess-side { width: 280px; flex: none; display: flex; flex-direction: column; border-right: 1px solid var(--border-2); background: #fbfcfe; }
.sess-actions { display: flex; gap: 8px; padding: 14px; border-bottom: 1px solid var(--border-2); }
.sess-new { flex: 1; }
.sess-list { flex: 1; overflow-y: auto; padding: 10px; }
.sess-item {
  display: flex; align-items: center; gap: 8px; padding: 10px 12px; margin-bottom: 4px;
  border-radius: var(--radius-sm); cursor: pointer; color: var(--text-2);
  transition: background 0.15s;
}
.sess-item:hover { background: var(--brand-50); }
.sess-item.active { background: var(--brand-50); color: var(--brand-600); font-weight: 600; }
.sess-ico { flex: none; color: var(--text-3); }
.sess-item.active .sess-ico { color: var(--brand); }
.sess-title { flex: 1; font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sess-del { color: var(--text-3); flex: none; }
.sess-del:hover { color: var(--danger); }

/* 右侧聊天 */
.chat-main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.chat-head { padding: 16px 20px; border-bottom: 1px solid var(--border-2); }
.chat-title { font-size: 15px; font-weight: 600; color: var(--text); }
.chat-sub { font-size: 12px; color: var(--text-3); margin-top: 3px; }

.msg-box { flex: 1; overflow-y: auto; padding: 20px; background: #f8fafc; }
.msg-empty { height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center; color: var(--text-3); gap: 10px; }
.msg-row { display: flex; gap: 10px; margin-bottom: 16px; }
.msg-row.user { flex-direction: row-reverse; }
.avatar {
  width: 34px; height: 34px; border-radius: 50%; flex: none;
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; font-weight: 700;
}
.avatar.user { background: var(--brand); color: #fff; }
.avatar.assistant { background: #fff; color: var(--brand-600); border: 1px solid var(--brand-100); }
.bubble { max-width: 72%; padding: 11px 15px; border-radius: 12px; font-size: 14px; line-height: 1.65; white-space: pre-wrap; word-break: break-word; }
.bubble.user { background: var(--brand); color: #fff; border-top-right-radius: 4px; }
.bubble.assistant { background: #fff; color: var(--text); border: 1px solid var(--border-2); border-top-left-radius: 4px; }
.bubble.typing { display: inline-flex; gap: 4px; align-items: center; }
.tdot { width: 6px; height: 6px; border-radius: 50%; background: var(--text-3); display: inline-block; animation: blink 1.2s infinite; }
.tdot:nth-child(2) { animation-delay: 0.2s; }
.tdot:nth-child(3) { animation-delay: 0.4s; }
@keyframes blink { 0%, 80%, 100% { opacity: 0.25; } 40% { opacity: 1; } }

.chat-input { display: flex; gap: 10px; padding: 14px 20px; border-top: 1px solid var(--border-2); background: #fff; }

/* 知识库 */
.kb-head { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.kb-tip { font-size: 12px; color: var(--text-3); }
</style>
