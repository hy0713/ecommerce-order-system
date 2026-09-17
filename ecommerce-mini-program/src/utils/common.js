/**
 * 通用工具方法
 */
// 价格格式化：分/元 统一展示为元（后端为元）
export function formatPrice(price) {
  if (price === null || price === undefined) return '0.00'
  return Number(price).toFixed(2)
}

// 时间格式化：yyyy-MM-dd HH:mm:ss
export function formatTime(time) {
  if (!time) return '-'
  return String(time).replace('T', ' ').slice(0, 19)
}

// 订单状态文案（与后端 OrderStatusEnum 对齐）
export const ORDER_STATUS = {
  0: '待支付',
  1: '已支付',
  2: '已发货',
  3: '已完成',
  4: '已取消'
}

// 登录态检查：无 token 跳登录页（返回是否已登录）
export function checkLogin() {
  const token = uni.getStorageSync('token')
  if (!token) {
    uni.navigateTo({ url: '/pages/login/login' })
    return false
  }
  return true
}
