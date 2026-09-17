import { defineStore } from 'pinia'
import { login as loginApi, logout as logoutApi, getUserInfo } from '../api'

// 用户状态：token + 用户信息，持久化到 localStorage
export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    userInfo: JSON.parse(localStorage.getItem('userInfo') || 'null')
  }),
  actions: {
    async login(form) {
      const res = await loginApi(form)
      this.token = res.data.token
      this.userInfo = res.data.userInfo
      localStorage.setItem('token', this.token)
      localStorage.setItem('userInfo', JSON.stringify(this.userInfo))
      return res
    },
    async fetchUserInfo() {
      const res = await getUserInfo()
      this.userInfo = res.data
      localStorage.setItem('userInfo', JSON.stringify(res.data))
    },
    async logout() {
      // 调用后端接口使 Redis 中 token 失效
      try {
        await logoutApi()
      } catch (e) {
        // 忽略登出接口异常，本地状态必须清除
      }
      this.token = ''
      this.userInfo = null
      localStorage.removeItem('token')
      localStorage.removeItem('userInfo')
    }
  }
})
