<template>
  <view class="order-list-page">
    <!-- 状态 Tab -->
    <view class="tabs">
      <view
        v-for="(t, key) in tabs"
        :key="key"
        class="tab-item"
        :class="{ active: activeStatus === key }"
        @click="switchTab(key)"
      >{{ t }}</view>
    </view>

    <!-- 订单卡片列表 -->
    <view class="order-list" v-if="orders.length">
      <view class="order-card" v-for="o in orders" :key="o.id" @click="goDetail(o)">
        <view class="order-head">
          <text class="order-no">订单号：{{ o.orderNo }}</text>
          <text class="order-status" :class="'s' + o.orderStatus">{{ o.orderStatusDesc }}</text>
        </view>
        <view class="order-goods" v-for="item in o.items" :key="item.productId">
          <image class="goods-img" :src="item.productIcon || '/static/placeholder.png'" mode="aspectFill" />
          <view class="goods-info">
            <view class="goods-name">{{ item.productName }}</view>
            <view class="goods-sub">￥{{ formatPrice(item.productPrice) }} x{{ item.productQuantity }}</view>
          </view>
        </view>
        <view class="order-total">
          <text class="total-label">共{{ totalQty(o) }}件 合计：</text>
          <text class="total-price">￥{{ formatPrice(o.totalAmount) }}</text>
        </view>
        <!-- 操作按钮 -->
        <view class="order-actions" v-if="o.orderStatus === 0 || o.orderStatus === 3">
          <template v-if="o.orderStatus === 0">
            <view class="action-btn cancel" @click.stop="cancelOrder(o)">取消订单</view>
            <view class="action-btn pay" @click.stop="payOrder(o)">立即支付</view>
          </template>
          <view v-if="o.orderStatus === 3" class="action-btn again" @click.stop="buyAgain(o)">再次购买</view>
        </view>
      </view>
    </view>
    <view class="empty" v-else-if="!loading">
      <u-empty text="暂无订单" mode="order"></u-empty>
    </view>
    <view class="load-more">{{ loading ? '加载中...' : (finished ? '没有更多了' : '') }}</view>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onLoad, onShow, onPullDownRefresh, onReachBottom } from '@dcloudio/uni-app'
import request from '../../utils/request'
import { formatPrice, ORDER_STATUS, checkLogin } from '../../utils/common'
import { useCartStore } from '../../store/cart'

const cartStore = useCartStore()
// 状态 Tab（全部 / 待支付 / 已支付 / 已发货 / 已完成 / 已取消）
const tabs = { '': '全部', 0: '待支付', 1: '已支付', 2: '已发货', 3: '已完成', 4: '已取消' }
const activeStatus = ref('')
const orders = ref([])
const pageNum = ref(1)
const pageSize = 10
const loading = ref(false)
const finished = ref(false)

// onLoad 之后紧跟的第一次 onShow 要跳过：否则首屏会重复请求，且 checkLogin 失败时连推两个登录页
let skipNextShow = false
// 请求序号：用于丢弃「已被更新请求取代」的慢响应
let reqSeq = 0

onLoad(async (options) => {
  skipNextShow = true
  if (!checkLogin()) return
  // 支持个人中心入口带状态跳入。
  // Tab 的 key 来自对象键，恒为字符串，这里必须保持字符串：
  // 若转成数字，activeStatus(1) === key('1') 严格比较恒 false，Tab 不会高亮。
  if (options.status !== undefined && options.status !== '') {
    activeStatus.value = String(options.status)
  }
  await loadOrders(true)
})

onShow(() => {
  if (skipNextShow) {
    skipNextShow = false
    return
  }
  if (!checkLogin()) return
  // 从详情页返回时刷新
  loadOrders(true)
})

function switchTab(key) {
  if (key === activeStatus.value) return
  activeStatus.value = key
  // reset 分支不再被 loading 守卫拦掉，否则切 Tab 只换了高亮、列表还是旧数据
  loadOrders(true)
}

async function loadOrders(reset) {
  // 翻页需要防重（否则重复追加）；reset（切 Tab / 下拉刷新）允许打断在途请求
  if (!reset && (loading.value || finished.value)) return
  const seq = ++reqSeq
  loading.value = true
  try {
    const params = { pageNum: reset ? 1 : pageNum.value, pageSize }
    if (activeStatus.value !== '') params.orderStatus = activeStatus.value
    const data = await request.get('/order/page', params)
    if (seq !== reqSeq) return   // 已有更新的请求发出，丢弃本次结果
    const records = data.records || []
    orders.value = reset ? records : orders.value.concat(records)
    pageNum.value = reset ? 2 : pageNum.value + 1
    finished.value = records.length < pageSize
  } catch (e) { /* 已提示 */ } finally {
    if (seq === reqSeq) loading.value = false
  }
}

function totalQty(o) {
  return (o.items || []).reduce((s, i) => s + i.productQuantity, 0)
}

function goDetail(o) {
  uni.navigateTo({ url: `/pages/order/detail?id=${o.id}` })
}

// 取消订单：仅待支付可取消，取消后库存自动回补
async function cancelOrder(o) {
  const res = await uni.showModal({ title: '提示', content: '确认取消该订单？库存将回补' })
  if (!res.confirm) return
  try {
    await request.post(`/order/${o.id}/cancel`)
    uni.showToast({ title: '已取消', icon: 'success' })
    loadOrders(true)
  } catch (e) { /* 已提示 */ }
}

// 立即支付：待支付 → 已支付
async function payOrder(o) {
  try {
    await request.post(`/order/${o.id}/pay`)
    uni.showToast({ title: '支付成功', icon: 'success' })
    loadOrders(true)
  } catch (e) { /* 已提示 */ }
}

// 再次购买：订单商品重新加入购物车
async function buyAgain(o) {
  uni.showLoading({ title: '处理中' })
  try {
    for (const item of o.items || []) {
      await request.post('/cart', { productId: item.productId, quantity: item.productQuantity })
    }
    uni.hideLoading()
    await cartStore.refresh().catch(() => {})
    uni.switchTab({ url: '/pages/cart/cart' })
  } catch (e) {
    uni.hideLoading()
  }
}

onPullDownRefresh(async () => {
  await loadOrders(true)
  uni.stopPullDownRefresh()
})

onReachBottom(() => loadOrders(false))
</script>

<style lang="scss" scoped>
.order-list-page {
  padding: $gap $gap 40rpx;
}
.tabs {
  display: flex;
  background: $bg-card;
  border-radius: $radius;
  padding: 8rpx;
  margin-bottom: $gap;
  box-shadow: $shadow-card;
}
.tab-item {
  flex: 1;
  text-align: center;
  padding: 16rpx 0;
  font-size: $fs-note;
  color: $text-2;
  border-radius: $radius-sm;
  transition: background 0.18s, color 0.18s;
}
.tab-item.active {
  background: $brand;
  color: #fff;
  font-weight: 600;
}
.order-card {
  background: $bg-card;
  border-radius: $radius;
  padding: 26rpx;
  margin-bottom: $gap;
  box-shadow: $shadow-card;
}
.order-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16rpx;
}
.order-no {
  font-size: $fs-mini;
  color: $text-3;
}
.order-status {
  font-size: 26rpx;
  font-weight: 600;
}
.s0 { color: $warning; }
.s1 { color: $brand; }
.s2 { color: #7c3aed; }
.s3 { color: $success; }
.s4 { color: $text-3; }
.order-goods {
  display: flex;
  align-items: center;
  padding: 16rpx 0;
  border-top: 1rpx solid $border-line;
}
.goods-img {
  width: 100rpx;
  height: 100rpx;
  border-radius: $radius-sm;
  background: $bg-muted;
  margin-right: 20rpx;
  flex-shrink: 0;
}
.goods-info {
  flex: 1;
  min-width: 0;
}
.goods-name {
  font-size: $fs-body;
  color: $text-1;
  @include ellipsis(1);
}
.goods-sub {
  font-size: $fs-note;
  color: $text-3;
  margin-top: 8rpx;
}
.order-total {
  text-align: right;
  padding-top: 18rpx;
  border-top: 1rpx solid $border-line;
  margin-top: 8rpx;
}
.total-label {
  font-size: $fs-note;
  color: $text-3;
}
.total-price {
  font-size: 34rpx;
  color: $price;
  font-weight: 700;
}
.order-actions {
  display: flex;
  justify-content: flex-end;
  gap: 20rpx;
  margin-top: 18rpx;
}
.action-btn {
  padding: 12rpx 34rpx;
  border-radius: $radius-pill;
  font-size: 26rpx;
  border: 1rpx solid #dfe3ea;
  color: $text-2;
  background: $bg-card;
}
.action-btn.pay {
  background: $brand-grad;
  color: #fff;
  border-color: transparent;
  box-shadow: $shadow-brand;
}
.action-btn.again {
  border-color: $brand;
  color: $brand;
}
.empty {
  padding: 100rpx 0;
}
.load-more {
  text-align: center;
  color: $text-3;
  font-size: $fs-note;
  padding: 20rpx 0;
}
</style>
