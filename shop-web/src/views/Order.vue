<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">订单管理</h2>
        <p class="page-desc">订单分页、状态查询、订单详情，管理员可发货 / 取消订单</p>
      </div>
      <el-button :icon="ShoppingCart" @click="openSimulate">模拟下单（闭环演示）</el-button>
    </div>

    <div class="card">
      <div class="toolbar">
        <!-- 注意：Element Plus 2.3.8 的 el-radio-button 只有 label 属性承载「值」，没有 value 属性 -->
        <el-radio-group v-model="statusTab" @change="load(1)">
          <el-radio-button label="all">全部</el-radio-button>
          <el-radio-button v-for="s in statusTabs" :key="s.value" :label="s.value">{{ s.label }}</el-radio-button>
        </el-radio-group>
        <div class="toolbar-right">
          <el-button text size="small" :icon="Refresh" @click="load()">刷新</el-button>
        </div>
      </div>

      <el-table :data="list" v-loading="loading" size="large" @row-click="showDetail" class="row-click">
        <el-table-column label="订单编号" min-width="200">
          <template #default="{ row }"><span class="num mono">{{ row.orderNo }}</span></template>
        </el-table-column>
        <el-table-column label="收货人" width="110">
          <template #default="{ row }">{{ row.receiverName }}</template>
        </el-table-column>
        <el-table-column label="商品数量" width="100">
          <template #default="{ row }"><span class="num">{{ qtyOf(row) }}</span></template>
        </el-table-column>
        <el-table-column label="订单金额" width="130">
          <template #default="{ row }"><span class="num price">{{ fmtMoney(row.totalAmount) }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <span class="dot" :class="statusOf(row).dot">{{ row.orderStatusDesc }}</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="170">
          <template #default="{ row }"><span class="num">{{ row.createTime }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click.stop="showDetail(row)">详情</el-button>
            <el-button v-if="isAdmin && row.orderStatus === 1" link type="primary" size="small" @click.stop="handleShip(row)">发货</el-button>
            <el-button v-if="isAdmin && row.orderStatus === 0" link type="danger" size="small" @click.stop="handleCancel(row)">取消</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无订单" :image-size="80" /></template>
      </el-table>

      <el-pagination layout="total, prev, pager, next" :total="total"
        :page-size="query.pageSize" :current-page="query.pageNum" @current-change="load" />
    </div>

    <!-- 订单详情（右侧抽屉） -->
    <el-drawer v-model="detailVisible" :size="520" class="order-drawer">
      <template #header>
        <div class="d-title">
          <span>订单详情</span>
          <span v-if="detail" class="dot d-status" :class="statusOf(detail).dot">{{ detail.orderStatusDesc }}</span>
        </div>
      </template>

      <div v-if="detail" class="drawer-body" v-loading="detailLoading">
        <div class="info-block">
          <div class="info-label">订单编号</div>
          <div class="info-value num mono">{{ detail.orderNo }}</div>
        </div>

        <div class="kv">
          <div class="kv-item"><span class="k">收货人</span><span class="v">{{ detail.receiverName }}</span></div>
          <div class="kv-item"><span class="k">联系电话</span><span class="v num">{{ detail.receiverPhone }}</span></div>
          <div class="kv-item full"><span class="k">收货地址</span><span class="v">{{ detail.receiverAddress }}</span></div>
        </div>

        <div class="sec-title">商品明细</div>
        <div class="items">
          <div v-for="(it, i) in detail.items" :key="i" class="item-row">
            <div class="item-name">{{ it.productName }}</div>
            <div class="item-meta num">{{ fmtMoney(it.productPrice) }} × {{ it.productQuantity }}</div>
            <div class="item-sub num">{{ fmtMoney(it.subtotal) }}</div>
          </div>
          <div class="total-row">
            <span>订单总额</span>
            <span class="num total-amt">{{ fmtMoney(detail.totalAmount) }}</span>
          </div>
        </div>

        <div class="sec-title">时间</div>
        <div class="kv">
          <div class="kv-item full"><span class="k">创建时间</span><span class="v num">{{ detail.createTime }}</span></div>
          <div class="kv-item full"><span class="k">支付时间</span><span class="v num">{{ detail.payTime || '—' }}</span></div>
        </div>

        <!-- 管理员操作：按真实角色控制；后端为唯一权威，非管理员触发将收到 403 -->
        <div v-if="isAdmin" class="admin-actions">
          <el-button
            v-if="detail.orderStatus === 1" type="primary" style="flex: 1"
            :loading="acting" @click="handleShip(detail)"
          >发货</el-button>
          <el-button
            v-if="detail.orderStatus === 0" type="danger" plain style="flex: 1"
            :loading="acting" @click="handleCancel(detail)"
          >取消订单</el-button>
          <div v-if="detail.orderStatus === 0" class="admin-hint">取消订单后将自动回补商品库存。</div>
        </div>
      </div>
    </el-drawer>

    <!-- 模拟下单：选购商品 → 自动确保收货地址 → 加购 → 提交订单 -->
    <el-dialog v-model="simulateVisible" title="模拟下单" width="500px">
      <div class="sim-flow">
        <div class="sim-step">选购商品</div><el-icon><ArrowRight /></el-icon>
        <div class="sim-step">创建收货地址</div><el-icon><ArrowRight /></el-icon>
        <div class="sim-step">加入购物车</div><el-icon><ArrowRight /></el-icon>
        <div class="sim-step">提交订单</div>
      </div>
      <el-form label-width="80px" style="margin-top: 18px">
        <el-form-item label="商品" required>
          <el-select v-model="simulate.productId" placeholder="选择商品" filterable style="width: 100%">
            <el-option v-for="p in onSaleProducts" :key="p.id" :label="`${p.name}（库存${p.stock}，${fmtMoney(p.price)}）`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="数量" required>
          <el-input-number v-model="simulate.quantity" :min="1" :max="99" />
        </el-form-item>
      </el-form>
      <div class="sim-note">仅用于演示完整业务链路，不涉及真实支付。</div>
      <template #footer>
        <el-button @click="simulateVisible = false">取消</el-button>
        <el-button type="primary" :loading="simulating" @click="handleSimulate">下单</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowRight, Refresh, ShoppingCart } from '@element-plus/icons-vue'
import {
  addAddress, addCart, adminCancelOrder, createOrder, getAddressList,
  getOrderDetail, getOrderPage, getProductPage, shipOrder
} from '../api'
import { useUserStore } from '../store/user'
import { ORDER_STATUS, fmtMoney } from '../utils/order'

const userStore = useUserStore()
const isAdmin = computed(() => userStore.userInfo?.role === 'ADMIN')

const statusTabs = [0, 1, 2, 3, 4].map((v) => ({ value: v, label: ORDER_STATUS[v].label }))
const statusOf = (o) => ORDER_STATUS[o.orderStatus] || { label: o.orderStatusDesc || '未知', dot: 'c-grey' }
const qtyOf = (row) => (row.items || []).reduce((s, it) => s + (it.productQuantity || 0), 0)

const list = ref([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ pageNum: 1, pageSize: 10 })
// 状态筛选：'all' | 0..4（'all' 时不向后端传 orderStatus 参数）
const statusTab = ref('all')

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const acting = ref(false)

const simulateVisible = ref(false)
const simulating = ref(false)
const onSaleProducts = ref([])
const simulate = reactive({ productId: null, quantity: 1 })

const load = async (page) => {
  if (page) query.pageNum = page
  loading.value = true
  try {
    const params = { ...query }
    if (statusTab.value !== 'all') params.orderStatus = statusTab.value
    const res = await getOrderPage(params)
    list.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

const showDetail = async (row) => {
  detailVisible.value = true
  detailLoading.value = true
  try {
    const res = await getOrderDetail(row.id)
    detail.value = res.data
  } finally {
    detailLoading.value = false
  }
}

const handleShip = async (row) => {
  await ElMessageBox.confirm(`确认对订单 ${row.orderNo} 发货？`, '提示', { type: 'warning' })
  acting.value = true
  try {
    await shipOrder(row.id)
    ElMessage.success('已发货')
    detailVisible.value = false
    load()
  } finally {
    acting.value = false
  }
}

const handleCancel = async (row) => {
  await ElMessageBox.confirm(`确认取消订单 ${row.orderNo}？\n取消订单后将自动回补商品库存。`, '提示', { type: 'warning' })
  acting.value = true
  try {
    await adminCancelOrder(row.id)
    ElMessage.success('已取消，库存已回补')
    detailVisible.value = false
    load()
  } finally {
    acting.value = false
  }
}

const openSimulate = async () => {
  const res = await getProductPage({ pageNum: 1, pageSize: 100, status: 1 })
  onSaleProducts.value = res.data.records
  simulateVisible.value = true
}

const handleSimulate = async () => {
  if (!simulate.productId) {
    ElMessage.warning('请选择商品')
    return
  }
  simulating.value = true
  try {
    let addresses = (await getAddressList()).data
    if (!addresses || addresses.length === 0) {
      await addAddress({ receiverName: '管理员', receiverPhone: '13800000000', address: '默认收货地址', isDefault: 1 })
      addresses = (await getAddressList()).data
    }
    const addressId = addresses.find((a) => a.isDefault === 1)?.id || addresses[0].id
    await addCart({ productId: simulate.productId, quantity: simulate.quantity })
    const orderRes = await createOrder({ addressId })
    ElMessage.success(`下单成功，订单号：${orderRes.data.orderNo}`)
    simulateVisible.value = false
    load()
  } finally {
    simulating.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 14px; padding: 16px 20px; border-bottom: 1px solid var(--border-2); flex-wrap: wrap; }
.toolbar-right { margin-left: auto; }
.row-click :deep(.el-table__row) { cursor: pointer; }
.mono { font-family: "JetBrains Mono", Consolas, monospace; font-size: 12px; }
.price { color: var(--text); font-weight: 600; }

.d-title { display: flex; align-items: center; gap: 12px; font-size: 16px; }
.d-status { font-size: 13px; }
.drawer-body { padding-bottom: 20px; }
.info-block { margin-bottom: 16px; }
.info-label { font-size: 12px; color: var(--text-3); }
.info-value { font-size: 16px; color: var(--text); margin-top: 4px; }

.kv { display: flex; flex-wrap: wrap; gap: 10px 24px; padding: 14px 16px; background: #f8fafc; border-radius: var(--radius-sm); }
.kv-item { display: flex; flex-direction: column; gap: 3px; min-width: 120px; }
.kv-item.full { width: 100%; }
.kv-item .k { font-size: 12px; color: var(--text-3); }
.kv-item .v { font-size: 14px; color: var(--text); }

.sec-title { font-size: 13px; font-weight: 600; color: var(--text); margin: 20px 0 10px; }
.items { border: 1px solid var(--border-2); border-radius: var(--radius-sm); overflow: hidden; }
.item-row { display: grid; grid-template-columns: 1fr auto auto; gap: 12px; align-items: center; padding: 10px 14px; border-bottom: 1px solid var(--border-2); }
.item-row:last-of-type { border-bottom: none; }
.item-name { font-size: 13px; color: var(--text); }
.item-meta { font-size: 12px; color: var(--text-3); }
.item-sub { font-size: 13px; color: var(--text); font-weight: 600; }
.total-row { display: flex; justify-content: space-between; align-items: center; padding: 12px 14px; background: #f8fafc; }
.total-amt { font-size: 16px; color: var(--brand-600); font-weight: 700; }

.admin-actions { margin-top: 22px; display: flex; flex-direction: column; gap: 10px; }
.admin-hint { font-size: 12px; color: var(--text-3); text-align: center; }

.sim-flow { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; color: var(--text-3); font-size: 13px; }
.sim-step { padding: 4px 10px; background: var(--brand-50); color: var(--brand-600); border-radius: 6px; font-size: 12px; }
.sim-note { margin-top: 6px; font-size: 12px; color: var(--text-3); }
</style>
