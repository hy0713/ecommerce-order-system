import { defineStore } from 'pinia'
import request from '../utils/request'

/**
 * 购物车全局状态（角标数量 + 选中项）
 * - count：购物车商品总件数（用于 TabBar 角标）
 * - 登录后刷新，退出后清空
 */
export const useCartStore = defineStore('cart', {
  state: () => ({
    count: 0,
    items: []
  }),
  getters: {
    // 选中商品件数（结算用）
    selectedCount: (state) => state.items.filter((i) => i.selected === 1).length,
    // 选中商品合计金额
    selectedTotal: (state) =>
      state.items
        .filter((i) => i.selected === 1)
        .reduce((sum, i) => sum + Number(i.productPrice) * i.quantity, 0)
  },
  actions: {
    // 拉取购物车列表并刷新角标
    async refresh() {
      const data = await request.get('/cart/list')
      this.items = data || []
      this.count = (data || []).reduce((sum, i) => sum + i.quantity, 0)
      return data
    },
    // 清空（退出登录时调用）
    clear() {
      this.count = 0
      this.items = []
    }
  }
})
