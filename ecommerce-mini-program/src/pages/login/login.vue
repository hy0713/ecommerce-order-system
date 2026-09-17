<template>
  <view class="login-page">
    <view class="logo-area">
      <view class="logo-mark">轻</view>
      <view class="app-name">轻量电商</view>
      <view class="app-desc">微信小程序用户端</view>
    </view>

    <!-- 账号密码登录（对接 /api/auth/login，与管理后台共用用户体系） -->
    <view class="form-card">
      <view class="form-item">
        <u-icon name="account" color="#9ca3af" size="22"></u-icon>
        <input class="form-input" v-model="form.username" placeholder="请输入用户名" />
      </view>
      <view class="form-item">
        <u-icon name="lock" color="#9ca3af" size="22"></u-icon>
        <input class="form-input" v-model="form.password" password placeholder="请输入密码" />
      </view>
      <u-button type="primary" text="登 录" :loading="loading" customStyle="margin-top: 40rpx" @click="doLogin"></u-button>

      <!-- 微信一键登录（演示版：模拟授权，实际引导账号密码登录） -->
      <view class="wx-login" @click="wxLogin">
        <u-icon name="weixin-fill" color="#19be6b" size="20"></u-icon>
        <text class="wx-login-text">微信一键登录（演示）</text>
      </view>

      <view class="tip">演示账号：admin / 123456</view>
    </view>
  </view>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useUserStore } from '../../store/user'
import { useCartStore } from '../../store/cart'

const userStore = useUserStore()
const cartStore = useCartStore()
const form = reactive({ username: '', password: '' })
const loading = ref(false)

async function doLogin() {
  if (!form.username || !form.password) {
    uni.showToast({ title: '请输入用户名和密码', icon: 'none' })
    return
  }
  loading.value = true
  try {
    await userStore.login({ username: form.username, password: form.password })
    uni.showToast({ title: '登录成功', icon: 'success' })
    // 刷新购物车角标
    cartStore.refresh().catch(() => {})
    setTimeout(() => uni.navigateBack({ fail: () => uni.reLaunch({ url: '/pages/index/index' }) }), 500)
  } catch (e) { /* 已提示 */ } finally {
    loading.value = false
  }
}

// 微信一键登录（演示版）：后端暂无微信 openid 体系，模拟授权后引导账号密码登录
function wxLogin() {
  uni.showModal({
    title: '演示模式',
    content: '微信一键登录为演示入口，当前后端未接入微信开放平台。请使用账号密码登录（体验账号 admin / 123456）',
    confirmText: '去登录',
    showCancel: false
  })
}
</script>

<style lang="scss" scoped>
.login-page {
  min-height: 100vh;
  background: linear-gradient(180deg, #3b82f6 0%, #2563eb 34%, $bg-page 34%);
  padding: 140rpx $gap 60rpx;
}
.logo-area {
  text-align: center;
  margin-bottom: 64rpx;
}
.logo-mark {
  width: 140rpx;
  height: 140rpx;
  margin: 0 auto;
  border-radius: 36rpx;
  background: rgba(255, 255, 255, 0.22);
  border: 4rpx solid rgba(255, 255, 255, 0.5);
  color: #fff;
  font-size: 68rpx;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  backdrop-filter: blur(6rpx);
}
.logo {
  font-size: 120rpx;
}
.app-name {
  font-size: 46rpx;
  font-weight: 700;
  color: #fff;
  margin-top: 24rpx;
  letter-spacing: 4rpx;
}
.app-desc {
  font-size: $fs-note;
  color: rgba(255, 255, 255, 0.86);
  margin-top: 10rpx;
}
.form-card {
  background: $bg-card;
  border-radius: $radius-lg;
  padding: 52rpx 40rpx;
  box-shadow: 0 16rpx 40rpx rgba(17, 24, 39, 0.1);
}
.form-item {
  display: flex;
  align-items: center;
  border-bottom: 1rpx solid $border-line;
  padding: 26rpx 8rpx;
}
.form-input {
  flex: 1;
  margin-left: 16rpx;
  font-size: 30rpx;
  color: $text-1;
}
.wx-login {
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 30rpx;
}
.wx-login-text {
  margin-left: 10rpx;
  color: $success;
  font-size: $fs-body;
}
.tip {
  text-align: center;
  color: $text-3;
  font-size: $fs-note;
  margin-top: 30rpx;
}
</style>
