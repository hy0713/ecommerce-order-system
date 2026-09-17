<template>
  <view class="mine-page">
    <!-- 用户信息区 -->
    <view class="user-card" @click="onUserClick">
      <image class="avatar" :src="userStore.userInfo?.avatar || '/static/avatar.png'" mode="aspectFill" />
      <view class="user-info">
        <view class="user-name">{{ userStore.isLogin ? userStore.userInfo?.username : '未登录' }}</view>
        <view class="user-sub">{{ userStore.isLogin ? '欢迎回来' : '点击登录' }}</view>
      </view>
    </view>

    <!-- 订单快捷入口 -->
    <view class="card">
      <view class="card-title">我的订单</view>
      <view class="order-entries">
        <view class="entry" @click="goOrders(0)">
          <u-icon name="rmb-circle-fill" color="#ff9900" size="30"></u-icon>
          <text>待支付</text>
        </view>
        <view class="entry" @click="goOrders(1)">
          <u-icon name="checkmark-circle-fill" color="#2979ff" size="30"></u-icon>
          <text>待发货</text>
        </view>
        <view class="entry" @click="goOrders(2)">
          <u-icon name="car-fill" color="#53a0ff" size="30"></u-icon>
          <text>待收货</text>
        </view>
        <view class="entry" @click="goOrders(3)">
          <u-icon name="checkmark-circle-fill" color="#19be6b" size="30"></u-icon>
          <text>已完成</text>
        </view>
      </view>
    </view>

    <!-- 功能菜单 -->
    <view class="card">
      <view class="menu-item" @click="goAddress">
        <u-icon name="map-fill" color="#2979ff" size="22"></u-icon>
        <text class="menu-text">收货地址</text>
        <u-icon name="arrow-right" color="#c0c4cc" size="18"></u-icon>
      </view>
      <view class="menu-item" @click="contact">
        <u-icon name="chat-fill" color="#19be6b" size="22"></u-icon>
        <text class="menu-text">智能客服</text>
        <u-icon name="arrow-right" color="#c0c4cc" size="18"></u-icon>
      </view>
      <view class="menu-item" @click="about">
        <u-icon name="info-circle-fill" color="#909399" size="22"></u-icon>
        <text class="menu-text">关于我们</text>
        <u-icon name="arrow-right" color="#c0c4cc" size="18"></u-icon>
      </view>
    </view>

    <!-- 退出登录 -->
    <view class="card" v-if="userStore.isLogin">
      <view class="logout-btn" @click="logout">退出登录</view>
    </view>
  </view>
</template>

<script setup>
import { onShow } from '@dcloudio/uni-app'
import { useUserStore } from '../../store/user'
import { useCartStore } from '../../store/cart'

const userStore = useUserStore()
const cartStore = useCartStore()

onShow(() => {
  // 登录后刷新用户信息
  if (userStore.isLogin) {
    userStore.fetchUserInfo().catch(() => {})
  }
})

function onUserClick() {
  if (!userStore.isLogin) {
    uni.navigateTo({ url: '/pages/login/login' })
  }
}

function goOrders(status) {
  if (!userStore.isLogin) {
    uni.navigateTo({ url: '/pages/login/login' })
    return
  }
  // 待发货=已支付，待收货=已发货
  const s = status === 1 ? 1 : status === 2 ? 2 : status
  uni.navigateTo({ url: `/pages/order/list?status=${s}` })
}

function goAddress() {
  uni.navigateTo({ url: '/pages/address/list' })
}

// 联系客服 = 跳智能客服 Agent 页（真正的 AI 客服，不是假电话弹窗）
function contact() {
  uni.switchTab({ url: '/pages/agent/agent' })
}

function about() {
  uni.showModal({ title: '关于我们', content: '轻量电商订单系统 C 端小程序 v1.0.0（演示版）' })
}

async function logout() {
  const res = await uni.showModal({ title: '提示', content: '确认退出登录？' })
  if (!res.confirm) return
  await userStore.logout()
  cartStore.clear()
  uni.showToast({ title: '已退出', icon: 'success' })
}
</script>

<style lang="scss" scoped>
.mine-page {
  padding: $gap $gap 60rpx;
}
.user-card {
  background: $brand-grad;
  border-radius: $radius-lg;
  padding: 44rpx 32rpx;
  display: flex;
  align-items: center;
  margin-bottom: $gap;
  color: #fff;
  box-shadow: 0 12rpx 28rpx rgba(37, 99, 235, 0.26);
}
.avatar {
  width: 120rpx;
  height: 120rpx;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.3);
  border: 4rpx solid rgba(255, 255, 255, 0.65);
}
.user-info {
  margin-left: 26rpx;
}
.user-name {
  font-size: 38rpx;
  font-weight: 700;
}
.user-sub {
  font-size: $fs-note;
  opacity: 0.88;
  margin-top: 8rpx;
}
.card {
  background: $bg-card;
  border-radius: $radius;
  padding: 26rpx;
  margin-bottom: $gap;
  box-shadow: $shadow-card;
}
.card-title {
  font-size: 30rpx;
  font-weight: 600;
  color: $text-1;
  margin-bottom: 26rpx;
  padding-left: 18rpx;
  position: relative;
}
.card-title::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 8rpx;
  height: 28rpx;
  border-radius: 4rpx;
  background: $brand-grad;
}
.order-entries {
  display: flex;
}
.entry {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12rpx;
  font-size: $fs-note;
  color: $text-2;
}
.menu-item {
  display: flex;
  align-items: center;
  padding: 26rpx 0;
  border-bottom: 1rpx solid $border-line;
}
.menu-item:last-child {
  border-bottom: none;
}
.menu-text {
  flex: 1;
  margin-left: 20rpx;
  font-size: $fs-body;
  color: $text-1;
}
.logout-btn {
  text-align: center;
  color: $danger;
  font-size: 30rpx;
  padding: 12rpx 0;
}
</style>
