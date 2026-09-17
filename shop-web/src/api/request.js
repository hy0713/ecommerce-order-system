import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

// Axios 封装：统一 baseURL / token / 响应解析（规格 4.4.1）
const request = axios.create({
  baseURL: '/api',
  timeout: 15000
})

// 请求拦截器：统一添加 Authorization: Bearer {token}
request.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截器：统一解析 Result（code=200 成功），非 200 弹窗提示；401 跳转登录
request.interceptors.response.use(
  (response) => {
    const res = response.data
    // 业务层 401：后端统一以 HTTP 200 + code 表达错误，需与网关的 HTTP 401 一起处理，
    // 否则「登录态失效」只会弹一句「操作失败」而不会跳回登录页
    if (res.code === 401) {
      clearLoginState()
      ElMessage.error(res.message || '登录已过期，请重新登录')
      router.push('/login')
      return Promise.reject(new Error(res.message || '未登录或登录已过期'))
    }
    if (res.code !== 200) {
      ElMessage.error(res.message || '操作失败')
      return Promise.reject(new Error(res.message || '操作失败'))
    }
    return res
  },
  (error) => {
    if (error.response && error.response.status === 401) {
      clearLoginState()
      ElMessage.error('登录已过期，请重新登录')
      router.push('/login')
    } else {
      ElMessage.error(error.response?.data?.message || '网络异常，请稍后重试')
    }
    return Promise.reject(error)
  }
)

function clearLoginState() {
  localStorage.removeItem('token')
  localStorage.removeItem('userInfo')
}

export default request
