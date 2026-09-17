// 订单状态与金额/时间格式化（与后端 OrderStatusEnum 一致）
// 0 待支付 / 1 已支付 / 2 已发货 / 3 已完成 / 4 已取消

export const ORDER_STATUS = {
  0: { label: '待支付', color: '#3b82f6', dot: 'c-blue' },
  1: { label: '已支付', color: '#22c55e', dot: 'c-green' },
  2: { label: '已发货', color: '#f59e0b', dot: 'c-orange' },
  3: { label: '已完成', color: '#0ea5e9', dot: 'c-blue' },
  4: { label: '已取消', color: '#cbd5e1', dot: 'c-grey' }
}

export const CANCELED = 4

// 金额：保留最多两位小数，带千分位（示例 ¥12,680.5）
export function fmtMoney(v, symbol = '¥') {
  const n = Number(v || 0)
  const s = n.toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 2 })
  return symbol + s
}

// 金额（不带符号，统计卡片用）
export function fmtNumber(v) {
  return Number(v || 0).toLocaleString('zh-CN')
}

// 后端时间是 "yyyy-MM-dd HH:mm:ss" 字符串，统一取日期部分做聚合
export function dayOf(ts) {
  return (ts || '').slice(0, 10)
}

// 把日期字符串 "2026-09-11" 显示成 "09-11"
export function shortDay(d) {
  return d ? d.slice(5) : ''
}
