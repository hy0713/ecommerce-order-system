/**
 * 智能客服 Agent 请求封装
 *
 * 路由：**走电商网关 8080**（网关有 /api/agent/** 路由转发到 Python 服务 :8000）。
 * 不要再直连 8000：那样调用方可以自报 user_id，等于任意用户可读他人会话记录。
 * 网关侧的鉴权策略：POST /api/agent/chat 可选鉴权（游客可用），
 * /api/agent/session/** 必须登录（身份由网关按 token 注入 X-User-Id）。
 *
 * 鉴权：请求带 Bearer token 供网关注入身份；需要鉴权的工具（查订单等）
 * 仍靠请求体里的 ecom_token 透传给 Agent。
 */
const BASE_URL = 'http://localhost:8080/api/agent'

function agentRequest(options) {
  return new Promise((resolve, reject) => {
    const token = uni.getStorageSync('token')
    let url = BASE_URL + options.url
    if (options.params) {
      const qs = Object.keys(options.params)
        .filter((k) => options.params[k] !== undefined && options.params[k] !== null)
        .map((k) => encodeURIComponent(k) + '=' + encodeURIComponent(options.params[k]))
        .join('&')
      if (qs) url += (url.indexOf('?') > -1 ? '&' : '?') + qs
    }
    uni.request({
      url,
      method: options.method || 'GET',
      data: options.data || {},
      // Agent 侧要调大模型 + 可能多轮工具调用，超时给足（web 端是 120s）
      timeout: 120000,
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
            // 会话相关接口需要登录；游客问答不受影响，因此这里只提示不跳转
            uni.showToast({ title: body.message || '请先登录', icon: 'none' })
            reject(body)
          } else {
            uni.showToast({ title: (body && body.message) || '操作失败', icon: 'none' })
            reject(body || new Error('请求失败'))
          }
        } else if (res.statusCode === 401) {
          uni.showToast({ title: '请先登录', icon: 'none' })
          reject(new Error('未登录'))
        } else if (res.statusCode === 429) {
          uni.showToast({ title: '请求过于频繁，请稍后重试', icon: 'none' })
          reject(new Error('429'))
        } else {
          const msg = (res.data && res.data.message) || `智能客服服务异常(${res.statusCode})`
          uni.showToast({ title: msg, icon: 'none' })
          reject(new Error(msg))
        }
      },
      fail: (err) => {
        // 网关不通（通常是没有 start-all）比 Agent 未启动更常见，提示要说清是「服务」
        uni.showToast({ title: '智能客服不可用，请确认后端服务已启动', icon: 'none' })
        reject(err)
      }
    })
  })
}

export default {
  get: (url, params) => agentRequest({ url, method: 'GET', params }),
  post: (url, data, params) => agentRequest({ url, method: 'POST', data, params }),
  delete: (url, params) => agentRequest({ url, method: 'DELETE', params })
}
