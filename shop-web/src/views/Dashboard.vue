<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">数据概览</h2>
        <p class="page-desc">查看当前电商系统核心运营数据</p>
      </div>
      <el-button text size="small" :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <!-- 统计卡片 -->
    <div class="stat-grid">
      <div v-for="c in cards" :key="c.label" class="card card-pad stat-card">
        <div class="stat-icon" :style="{ background: c.bg, color: c.color }">
          <el-icon :size="22"><component :is="c.icon" /></el-icon>
        </div>
        <div class="stat-body">
          <div class="stat-label">{{ c.label }}</div>
          <div class="stat-value num">{{ c.value }}</div>
          <div v-if="c.note" class="stat-note">{{ c.note }}</div>
        </div>
      </div>
    </div>

    <!-- 销售趋势 + 订单状态 -->
    <el-row :gutter="16" class="mt16">
      <el-col :xs="24" :lg="16">
        <div class="card card-pad">
          <div class="chart-head">
            <div>
              <div class="card-title">销售额趋势</div>
              <div class="card-sub">按下单日期汇总，不含已取消订单</div>
            </div>
            <el-radio-group v-model="trendDays" size="small">
              <el-radio-button :label="7">近 7 天</el-radio-button>
              <el-radio-button :label="30">近 30 天</el-radio-button>
            </el-radio-group>
          </div>
          <LineChart :points="trendPoints" :height="240" />
        </div>
      </el-col>
      <el-col :xs="24" :lg="8">
        <div class="card card-pad donut-card">
          <div class="chart-head">
            <div>
              <div class="card-title">订单状态</div>
              <div class="card-sub">各状态订单数量分布</div>
            </div>
          </div>
          <DonutChart :segments="donutSegments" :size="200" center-label="订单总数" />
        </div>
      </el-col>
    </el-row>

    <!-- 最近订单 -->
    <div class="card mt16">
      <div class="recent-head">
        <div class="card-title">最近订单</div>
        <el-button text type="primary" size="small" @click="$router.push('/orders')">查看全部</el-button>
      </div>
      <el-table :data="recent" size="large" :show-header="true">
        <el-table-column prop="orderNo" label="订单编号" min-width="200">
          <template #default="{ row }"><span class="num mono">{{ row.orderNo }}</span></template>
        </el-table-column>
        <el-table-column prop="receiverName" label="收货人" width="110" />
        <el-table-column label="金额" width="130">
          <template #default="{ row }"><span class="num">{{ fmtMoney(row.totalAmount) }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <span class="dot" :class="statusOf(row).dot">{{ statusOf(row).label }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" min-width="170">
          <template #default="{ row }"><span class="num">{{ row.createTime }}</span></template>
        </el-table-column>
        <template #empty><el-empty description="暂无订单" :image-size="70" /></template>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { AlarmClock, Coin, Goods, Refresh, Tickets } from '@element-plus/icons-vue'
import LineChart from '../components/charts/LineChart.vue'
import DonutChart from '../components/charts/DonutChart.vue'
import { getOrderPage, getOrderStats, getProductPage } from '../api'
import { CANCELED, ORDER_STATUS, dayOf, fmtMoney, fmtNumber, shortDay } from '../utils/order'

const stats = ref({ totalOrders: 0, totalSales: 0, todayOrders: 0, todaySales: 0 })
const productTotal = ref(0)
const orders = ref([])
const trendDays = ref(7)

const statusOf = (o) => ORDER_STATUS[o.orderStatus] || { label: o.orderStatusDesc || '未知', dot: 'c-grey' }

const cards = computed(() => [
  { label: '订单总量', value: fmtNumber(stats.value.totalOrders), icon: Tickets, color: '#3b82f6', bg: '#eff6ff' },
  { label: '总销售额', value: fmtMoney(stats.value.totalSales), icon: Coin, color: '#22c55e', bg: '#f0fdf4', note: '不含已取消订单' },
  { label: '今日订单', value: fmtNumber(stats.value.todayOrders), icon: AlarmClock, color: '#f59e0b', bg: '#fffbeb' },
  { label: '商品总数', value: fmtNumber(productTotal.value), icon: Goods, color: '#8b5cf6', bg: '#f5f3ff' }
])

// ---- 销售额趋势：近 N 天，按下单日汇总（排除已取消）----
const trendPoints = computed(() => {
  const n = trendDays.value
  const buckets = []
  const today = new Date()
  for (let i = n - 1; i >= 0; i--) {
    const d = new Date(today)
    d.setDate(today.getDate() - i)
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    buckets.push({ key, value: 0 })
  }
  const map = Object.fromEntries(buckets.map((b) => [b.key, b]))
  for (const o of orders.value) {
    if (o.orderStatus === CANCELED) continue
    const b = map[dayOf(o.createTime)]
    if (b) b.value += Number(o.totalAmount || 0)
  }
  return buckets.map((b) => ({ label: shortDay(b.key), value: Math.round(b.value * 100) / 100 }))
})

// ---- 订单状态分布 ----
const donutSegments = computed(() => {
  const count = { 0: 0, 1: 0, 2: 0, 3: 0, 4: 0 }
  for (const o of orders.value) {
    if (count[o.orderStatus] !== undefined) count[o.orderStatus]++
  }
  return [0, 1, 2, 3, 4].map((s) => ({ label: ORDER_STATUS[s].label, value: count[s], color: ORDER_STATUS[s].color }))
})

// ---- 最近订单（列表按创建时间倒序，取前 6 条）----
const recent = computed(() => orders.value.slice(0, 6))

const load = async () => {
  const s = await getOrderStats()
  stats.value = s.data
  const p = await getProductPage({ pageNum: 1, pageSize: 1 })
  productTotal.value = p.data.total
  // 取较新的一页订单用于趋势 / 状态分布 / 最近订单（真实数据，客户端聚合）
  const o = await getOrderPage({ pageNum: 1, pageSize: 100 })
  orders.value = o.data.records || []
}

onMounted(load)
</script>

<style scoped>
.mt16 { margin-top: 16px; }
.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; }
.stat-card { display: flex; align-items: center; gap: 14px; }
.stat-icon { width: 48px; height: 48px; border-radius: 12px; flex: none; display: flex; align-items: center; justify-content: center; }
.stat-body { min-width: 0; }
.stat-label { font-size: 13px; color: var(--text-3); }
.stat-value { font-size: 26px; font-weight: 700; color: var(--text); margin-top: 4px; line-height: 1.1; }
.stat-note { font-size: 11px; color: var(--text-3); margin-top: 4px; }
.chart-head { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 14px; gap: 12px; }
.donut-card { min-height: 300px; }
.recent-head { display: flex; align-items: center; justify-content: space-between; padding: 16px 20px 4px; }
.mono { font-family: "JetBrains Mono", Consolas, monospace; font-size: 12px; }
@media (max-width: 1100px) { .stat-grid { grid-template-columns: repeat(2, 1fr); } }
</style>
