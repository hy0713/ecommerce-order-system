<template>
  <view class="order-detail-page" v-if="order">
    <!-- 状态区 -->
    <view class="status-card" :class="'bg' + order.orderStatus">
      <view class="status-title">{{ order.orderStatusDesc }}</view>
      <view class="status-tip" v-if="order.orderStatus === 0">请尽快完成支付，超时订单将自动取消</view>
      <view class="status-tip" v-else-if="order.orderStatus === 4">订单已取消，库存已回补</view>
    </view>

    <!-- 收货信息 -->
    <view class="card">
      <view class="addr-name">{{ order.receiverName }} {{ order.receiverPhone }}</view>
      <view class="addr-detail">{{ order.receiverAddress }}</view>
    </view>

    <!-- 商品明细 -->
    <view class="card">
      <view class="goods-item" v-for="item in order.items" :key="item.productId">
        <image class="goods-img" :src="item.productIcon || '/static/placeholder.png'" mode="aspectFill" />
        <view class="goods-info">
          <view class="goods-name">{{ item.productName }}</view>
          <view class="goods-sub">￥{{ formatPrice(item.productPrice) }} x{{ item.productQuantity }}</view>
        </view>
        <view class="goods-subtotal">￥{{ formatPrice(item.subtotal) }}</view>
      </view>
    </view>

    <!-- 订单信息 -->
    <view class="card">
      <view class="info-row"><text>订单号</text><text>{{ order.orderNo }}</text></view>
      <view class="info-row"><text>下单时间</text><text>{{ formatTime(order.createTime) }}</text></view>
      <view class="info-row"><text>支付时间</text><text>{{ formatTime(order.payTime) }}</text></view>
      <view class="info-row total"><text>实付金额</text><text class="total-price">￥{{ formatPrice(order.totalAmount) }}</text></view>
    </view>

    <!-- 底部操作 -->
    <view class="bottom-bar" v-if="order.orderStatus === 0 || order.orderStatus === 3">
      <template v-if="order.orderStatus === 0">
        <view class="action-btn" @click="cancelOrder">取消订单</view>
        <view class="action-btn primary" @click="payOrder">立即支付</view>
      </template>
      <view v-if="order.orderStatus === 3" class="action-btn primary" @click="afterSale">申请售后</view>
    </view>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import request from '../../utils/request'
import { formatPrice, formatTime, checkLogin } from '../../utils/common'

const order = ref(null)

onLoad(async (options) => {
  if (!checkLogin()) return
  if (!options.id) {
    uni.showToast({ title: '参数错误', icon: 'none' })
    return
  }
  await load(options.id)
})

async function load(id) {
  try {
    order.value = await request.get(`/order/${id}`)
  } catch (e) { /* 已提示 */ }
}

async function cancelOrder() {
  const res = await uni.showModal({ title: '提示', content: '确认取消该订单？库存将回补' })
  if (!res.confirm) return
  try {
    await request.post(`/order/${order.value.id}/cancel`)
    uni.showToast({ title: '已取消', icon: 'success' })
    await load(order.value.id)
  } catch (e) { /* 已提示 */ }
}

async function payOrder() {
  try {
    await request.post(`/order/${order.value.id}/pay`)
    uni.showToast({ title: '支付成功', icon: 'success' })
    await load(order.value.id)
  } catch (e) { /* 已提示 */ }
}

// 售后为演示占位（后端无售后模块）
function afterSale() {
  uni.showModal({ title: '提示', content: '售后功能演示版暂未开放，请稍后重试' })
}
</script>

<style lang="scss" scoped>
.order-detail-page {
  padding: $gap $gap 180rpx;
}
.status-card {
  border-radius: $radius-lg;
  padding: 44rpx 32rpx;
  margin-bottom: $gap;
  color: #fff;
}
.bg0 { background: linear-gradient(135deg, #f59e0b, #fbbf24); box-shadow: 0 12rpx 26rpx rgba(245, 158, 11, 0.26); }
.bg1 { background: $brand-grad; box-shadow: $shadow-brand; }
.bg2 { background: linear-gradient(135deg, #3b82f6, #2563eb); box-shadow: $shadow-brand; }
.bg3 { background: linear-gradient(135deg, #10b981, #34d399); box-shadow: 0 12rpx 26rpx rgba(16, 185, 129, 0.26); }
.bg4 { background: linear-gradient(135deg, #9ca3af, #cbd5e1); box-shadow: 0 12rpx 26rpx rgba(156, 163, 175, 0.24); }
.status-title {
  font-size: 42rpx;
  font-weight: 700;
}
.status-tip {
  font-size: $fs-note;
  margin-top: 14rpx;
  opacity: 0.92;
}
.card {
  background: $bg-card;
  border-radius: $radius;
  padding: 26rpx;
  margin-bottom: $gap;
  box-shadow: $shadow-card;
}
.addr-name {
  font-size: 30rpx;
  font-weight: 600;
  color: $text-1;
  margin-bottom: 10rpx;
}
.addr-detail {
  font-size: $fs-note;
  color: $text-3;
}
.goods-item {
  display: flex;
  align-items: center;
  padding: 16rpx 0;
  border-bottom: 1rpx solid $border-line;
}
.goods-item:last-child {
  border-bottom: none;
}
.goods-img {
  width: 110rpx;
  height: 110rpx;
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
.goods-subtotal {
  color: $text-1;
  font-size: $fs-body;
  font-weight: 600;
}
.info-row {
  display: flex;
  justify-content: space-between;
  font-size: $fs-note;
  color: $text-2;
  padding: 14rpx 0;
}
.info-row.total {
  border-top: 1rpx solid $border-line;
  margin-top: 10rpx;
  padding-top: 22rpx;
  font-weight: 600;
}
.total-price {
  color: $price;
  font-size: 36rpx;
  font-weight: 700;
}
.bottom-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  background: $bg-card;
  padding: 16rpx $gap calc(16rpx + env(safe-area-inset-bottom));
  display: flex;
  justify-content: flex-end;
  gap: 20rpx;
  box-shadow: $shadow-float;
}
.action-btn {
  padding: 18rpx 44rpx;
  border-radius: $radius-pill;
  font-size: $fs-body;
  border: 1rpx solid #dfe3ea;
  color: $text-2;
  background: $bg-card;
}
.action-btn.primary {
  background: $brand-grad;
  color: #fff;
  border-color: transparent;
  box-shadow: $shadow-brand;
}
</style>
