import { defineStore } from 'pinia'
import request from '../utils/request'

/**
 * 用户登录态（规格 5.1/5.2）
 * - 登录成功存 token + userInfo 到 Pinia 与本地存储
 * - 退出登录调用后端接口使 Redis 中 token 失效
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: uni.getStorageSync('token') || '',
    userInfo: uni.getStorageSync('userInfo') || null
  }),
  getters: {
    isLogin: (state) => !!state.token
  },
  actions: {
    // 账号密码登录（对接 /api/auth/login）
    async login(form) {
      const data = await request.post('/auth/login', form)
      this.token = data.token
      this.userInfo = data.userInfo
      uni.setStorageSync('token', data.token)
      uni.setStorageSync('userInfo', data.userInfo)
      return data
    },
    // 刷新用户信息
    async fetchUserInfo() {
      const data = await request.get('/user/info')
      this.userInfo = data
      uni.setStorageSync('userInfo', data)
    },
    // 退出登录：后端 Redis token 失效 + 本地清除
    async logout() {
      try {
        await request.post('/auth/logout')
      } catch (e) {
        // 忽略登出接口异常，本地状态必须清除
      }
      this.token = ''
      this.userInfo = null
      uni.removeStorageSync('token')
      uni.removeStorageSync('userInfo')
    }
  }
})
