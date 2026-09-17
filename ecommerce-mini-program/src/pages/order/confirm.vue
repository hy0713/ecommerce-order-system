<template>
  <view class="confirm-page" v-if="isLogin">
    <!-- 收货地址 -->
    <view class="card address-card" @click="chooseAddress">
      <view v-if="address">
        <view class="addr-line1">
          <text class="addr-name">{{ address.receiverName }}</text>
          <text class="addr-phone">{{ address.receiverPhone }}</text>
          <text class="addr-default" v-if="address.isDefault === 1">默认</text>
        </view>
        <view class="addr-detail">{{ address.address }}</view>
      </view>
      <view v-else class="addr-empty">请选择收货地址 ></view>
    </view>

    <!-- 商品清单 -->
    <view class="card">
      <view class="goods-item" v-for="item in selectedItems" :key="item.id">
        <image class="goods-img" :src="item.productIcon || '/static/placeholder.png'" mode="aspectFill" />
        <view class="goods-info">
          <view class="goods-name">{{ item.productName }}</view>
          <view class="goods-bottom">
            <text class="goods-price">￥{{ formatPrice(item.productPrice) }}</text>
            <text class="goods-qty">x{{ item.quantity }}</text>
          </view>
        </view>
        <view class="goods-subtotal">￥{{ formatPrice(Number(item.productPrice) * item.quantity) }}</view>
      </view>
      <view class="empty-goods" v-if="!selectedItems.length">暂无可结算商品</view>
    </view>

    <!-- 金额汇总 -->
    <view class="card amount-card">
      <view class="amount-row"><text>商品总价</text><text>￥{{ formatPrice(cartStore.selectedTotal) }}</text></view>
      <view class="amount-row"><text>运费</text><text>￥0.00</text></view>
      <view class="amount-row total"><text>实付金额</text><text class="total-price">￥{{ formatPrice(cartStore.selectedTotal) }}</text></view>
    </view>

    <!-- 提交按钮 -->
    <view class="submit-bar">
      <view class="submit-btn" :class="{ disabled: submitting || !address || !selectedItems.length }" @click="submit">
        {{ submitting ? '提交中...' : '提交订单' }}
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { onLoad, onShow, onUnload } from '@dcloudio/uni-app'
import { storeToRefs } from 'pinia'
import request from '../../utils/request'
import { formatPrice, checkLogin } from '../../utils/common'
import { useUserStore } from '../../store/user'
import { useCartStore } from '../../store/cart'

const userStore = useUserStore()
const cartStore = useCartStore()
const { isLogin } = storeToRefs(userStore)
const address = ref(null)
const submitting = ref(false)

// 选中商品（结算范围 = 购物车 selected=1）
const selectedItems = computed(() => cartStore.items.filter((i) => i.selected === 1))

onLoad(async () => {
  if (!checkLogin()) return
  // 监听地址选择返回
  uni.$on('addressSelected', (addr) => {
    address.value = addr
  })
  await Promise.all([loadAddress(), refreshCart()])
})

onShow(() => {
  if (!userStore.isLogin) return
  refreshCart()
})

onUnload(() => {
  uni.$off('addressSelected')
})

async function loadAddress() {
  try {
    const list = await request.get('/user/address/list')
    if (list && list.length) {
      address.value = list.find((a) => a.isDefault === 1) || list[0]
    }
  } catch (e) { /* 已提示 */ }
}

async function refreshCart() {
  await cartStore.refresh().catch(() => {})
}

// 选择地址：跳地址列表（选择模式）
function chooseAddress() {
  uni.navigateTo({ url: '/pages/address/list?select=1' })
}

async function submit() {
  if (!address.value) {
    uni.showToast({ title: '请选择收货地址', icon: 'none' })
    return
  }
  if (!selectedItems.value.length) {
    uni.showToast({ title: '没有可结算的商品', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    const data = await request.post('/order', { addressId: address.value.id })
    uni.showToast({ title: '下单成功', icon: 'success' })
    await cartStore.refresh().catch(() => {})
    uni.redirectTo({ url: `/pages/order/detail?id=${data.id}` })
  } catch (e) { /* 已提示 */ } finally {
    submitting.value = false
  }
}
</script>

<style lang="scss" scoped>
.confirm-page {
  padding: $gap $gap 180rpx;
}
.card {
  background: $bg-card;
  border-radius: $radius;
  padding: 26rpx;
  margin-bottom: $gap;
  box-shadow: $shadow-card;
}
.address-card .addr-line1 {
  display: flex;
  align-items: center;
  margin-bottom: 12rpx;
}
.addr-name {
  font-size: 30rpx;
  font-weight: 600;
  color: $text-1;
}
.addr-phone {
  font-size: $fs-note;
  color: $text-2;
  margin-left: 20rpx;
}
.addr-default {
  font-size: 20rpx;
  color: $brand;
  border: 1rpx solid $brand;
  border-radius: 6rpx;
  padding: 2rpx 10rpx;
  margin-left: 16rpx;
}
.addr-detail {
  font-size: $fs-note;
  color: $text-3;
}
.addr-empty {
  color: $text-3;
  font-size: $fs-body;
}
.goods-item {
  display: flex;
  align-items: center;
  padding: 18rpx 0;
  border-bottom: 1rpx solid $border-line;
}
.goods-item:last-child {
  border-bottom: none;
}
.goods-img {
  width: 120rpx;
  height: 120rpx;
  border-radius: $radius-sm;
  background: $bg-muted;
  flex-shrink: 0;
}
.goods-info {
  flex: 1;
  min-width: 0;
  margin: 0 20rpx;
}
.goods-name {
  font-size: $fs-body;
  color: $text-1;
  @include ellipsis(1);
}
.goods-bottom {
  margin-top: 12rpx;
  display: flex;
  justify-content: space-between;
}
.goods-price {
  color: $price;
  font-size: $fs-body;
  font-weight: 600;
}
.goods-qty {
  color: $text-3;
  font-size: $fs-note;
}
.goods-subtotal {
  color: $text-1;
  font-size: $fs-body;
  font-weight: 600;
}
.empty-goods {
  text-align: center;
  color: $text-3;
  padding: 40rpx 0;
}
.amount-row {
  display: flex;
  justify-content: space-between;
  font-size: $fs-body;
  color: $text-2;
  padding: 12rpx 0;
}
.amount-row.total {
  font-weight: 600;
  color: $text-1;
  border-top: 1rpx solid $border-line;
  margin-top: 10rpx;
  padding-top: 22rpx;
}
.total-price {
  color: $price;
  font-size: 38rpx;
  font-weight: 700;
}
.submit-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  background: $bg-card;
  padding: 16rpx $gap calc(16rpx + env(safe-area-inset-bottom));
  box-shadow: $shadow-float;
}
.submit-btn {
  background: $brand-grad;
  color: #fff;
  text-align: center;
  padding: 24rpx 0;
  border-radius: $radius-pill;
  font-size: 32rpx;
  box-shadow: $shadow-brand;
}
.submit-btn.disabled {
  opacity: 0.45;
  box-shadow: none;
}
</style>
