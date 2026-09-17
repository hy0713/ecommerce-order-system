/**
 * uni.request 统一封装（规格 5.1）
 * - 基础地址：http://localhost:8080/api（微服务网关）
 * - 请求拦截：自动携带 Authorization: Bearer {token}
 * - 响应拦截：code=200 返回 data；非 200 弹错误提示；401 清登录态跳登录页
 */
const BASE_URL = 'http://localhost:8080/api'

function request(options) {
  return new Promise((resolve, reject) => {
    const token = uni.getStorageSync('token')
    // 支持 query 参数（如 PUT /cart/select-all?selected=false）
    let url = BASE_URL + options.url
    if (options.params) {
      const qs = Object.keys(options.params)
        .filter((k) => {
          const v = options.params[k]
          if (v === undefined || v === null) return false
          // 防线：传进来的如果是对象（典型错误是写成 axios 风格的 { params: {...} }），
          // encodeURIComponent 会得到 "[object Object]"，请求照发但后端取不到参数，
          // 只回一个含糊的 400「参数错误」。这里把它显式暴露出来，不要静默吞掉。
          if (typeof v === 'object') {
            console.error(`[request] query 参数「${k}」不是原始值，已忽略。请传扁平的 { key: value }，不要传 { params: {...} }`)
            return false
          }
          return true
        })
        .map((k) => encodeURIComponent(k) + '=' + encodeURIComponent(options.params[k]))
        .join('&')
      if (qs) url += (url.indexOf('?') > -1 ? '&' : '?') + qs
    }
    uni.request({
      url,
      method: options.method || 'GET',
      data: options.data || {},
      timeout: 15000,
      header: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: 'Bearer ' + token } : {})
      },
      success: (res) => {
        if (res.statusCode === 200) {
          const body = res.data
          if (body && body.code === 200) {
            resolve(body.data)
          } else if (body && body.code === 401) {
            handleUnauthorized()
            reject(body)
          } else {
            uni.showToast({ title: (body && body.message) || '操作失败', icon: 'none' })
            reject(body || new Error('请求失败'))
          }
        } else if (res.statusCode === 401) {
          handleUnauthorized()
          reject(new Error('未登录'))
        } else if (res.statusCode === 429) {
          uni.showToast({ title: '请求过于频繁，请稍后重试', icon: 'none' })
          reject(new Error('429'))
        } else {
          const msg = (res.data && res.data.message) || `服务异常(${res.statusCode})`
          uni.showToast({ title: msg, icon: 'none' })
          reject(new Error(msg))
        }
      },
      fail: (err) => {
        uni.showToast({ title: '网络异常，请稍后重试', icon: 'none' })
        reject(err)
      }
    })
  })
}

// 401：清除本地登录态并跳转登录页（登录页跳转使用 reLaunch 防止返回死循环）
function handleUnauthorized() {
  uni.removeStorageSync('token')
  uni.removeStorageSync('userInfo')
  uni.showToast({ title: '登录已过期，请重新登录', icon: 'none' })
  const pages = getCurrentPages()
  const current = pages.length ? pages[pages.length - 1].route : ''
  if (current && current.indexOf('login') === -1) {
    setTimeout(() => uni.reLaunch({ url: '/pages/login/login' }), 800)
  }
}

// 说明：封装内部靠 options.params 拼 query，而 uni.request 对 GET 会把 data 也拼到 URL。
// 因此四个方法都必须把第 3 个参数（params）继续透传，否则 PUT /cart/select-all?selected=xx
// 这类「body 为空、参数走 query」的接口会直接 400（曾导致购物车勾选/全选/立即购买全废）。
// 约定：第 3 参是**扁平的 query 对象**，如 { selected: false }。
// 不要写成 axios 风格的 { params: { selected: false } } —— 那会被序列化成 ?params=[object Object]，
// 后端 @RequestParam 取不到值，只返回含糊的「参数错误」。
export default {
  get: (url, data, params) => request({ url, method: 'GET', data, params }),
  post: (url, data, params) => request({ url, method: 'POST', data, params }),
  put: (url, data, params) => request({ url, method: 'PUT', data, params }),
  delete: (url, data, params) => request({ url, method: 'DELETE', data, params })
}
