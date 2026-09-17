// 智能客服 Agent 专用 API（独立 axios 实例：超时更长、响应体解析口径一致）
// baseURL '/api/agent' → **电商网关 8080** → 转发到 Python 服务 :8000。
// 必须经网关：直连 8000 时调用方可以自报 user_id，等于任意用户可读他人会话。
import axios from 'axios'
import { ElMessage } from 'element-plus'

const agentRequest = axios.create({
  baseURL: '/api/agent',
  timeout: 120000
})

// 请求拦截：Bearer token 供网关鉴权（会话接口必须登录）；
// ecom_token 供 Agent 工具链调用电商接口（查订单/库存）
agentRequest.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = 'Bearer ' + token
    config.headers.ecom_token = token
  }
  return config
})

// 响应拦截：解析 Result{code,message,data}，非 200 弹窗
agentRequest.interceptors.response.use(
  res => {
    const body = res.data
    if (body && body.code === 200) {
      return body
    }
    if (body && body.code === 401) {
      ElMessage.error('登录已过期，请重新登录')
    } else {
      ElMessage.error(body?.message || '操作失败')
    }
    return Promise.reject(new Error(body?.message || '操作失败'))
  },
  error => {
    ElMessage.error(error.response?.data?.message || '智能客服服务连接失败，请确认后端已启动')
    return Promise.reject(error)
  }
)

// ===== 会话管理（身份由网关按 token 注入，前端不再传 user_id）=====
export const agentSessionList = () => agentRequest.get('/session/list')
export const agentSessionCreate = (data = {}) => agentRequest.post('/session/create', data)
export const agentSessionDelete = (id) => agentRequest.delete(`/session/delete/${id}`)
export const agentSessionMessages = (id) => agentRequest.get(`/session/${id}/messages`)

// ===== 对话 =====
export const agentChat = (data) => agentRequest.post('/chat', data)

// ===== 知识库 =====
export const agentKnowledgeUpload = (formData) => agentRequest.post('/knowledge/upload', formData)
export const agentKnowledgeList = (params) => agentRequest.get('/knowledge/list', { params })
export const agentKnowledgeDelete = (id) => agentRequest.delete(`/knowledge/delete/${id}`)

// ===== 工具配置 =====
export const agentToolList = () => agentRequest.get('/tool/list')
export const agentToolStatus = (id, status) => agentRequest.put(`/tool/status/${id}`, { status })
