<template>
  <view class="address-page">
    <!-- 地址列表 -->
    <view class="addr-list" v-if="list.length">
      <view class="addr-card" v-for="a in list" :key="a.id" @click="onClick(a)">
        <view class="addr-line1">
          <text class="addr-name">{{ a.receiverName }}</text>
          <text class="addr-phone">{{ a.receiverPhone }}</text>
          <text class="addr-default" v-if="a.isDefault === 1">默认</text>
        </view>
        <view class="addr-detail">{{ a.address }}</view>
        <view class="addr-actions">
          <view class="addr-action" @click.stop="setDefault(a)" v-if="a.isDefault !== 1">
            <u-icon name="checkmark-circle" color="#909399" size="20"></u-icon>
            <text>设为默认</text>
          </view>
          <view class="addr-action" @click.stop="edit(a)">
            <u-icon name="edit-pen" color="#909399" size="20"></u-icon>
            <text>编辑</text>
          </view>
          <view class="addr-action" @click.stop="remove(a)">
            <u-icon name="trash" color="#fa3534" size="20"></u-icon>
            <text>删除</text>
          </view>
        </view>
      </view>
    </view>
    <view class="empty" v-else>
      <u-empty text="暂无收货地址" mode="list"></u-empty>
    </view>

    <!-- 新增地址 -->
    <view class="add-bar">
      <view class="add-btn" @click="edit(null)">+ 新增收货地址</view>
    </view>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import request from '../../utils/request'
import { checkLogin } from '../../utils/common'

// select=1 表示选择模式（订单确认页跳入），点击地址后回传并返回
const selectMode = ref(false)
const list = ref([])

onLoad((options) => {
  if (!checkLogin()) return
  selectMode.value = options.select === '1'
})

onShow(async () => {
  if (!checkLogin()) return
  await load()
})

async function load() {
  try {
    list.value = await request.get('/user/address/list')
  } catch (e) { /* 已提示 */ }
}

// 点击：选择模式回传地址；否则进编辑
function onClick(a) {
  if (selectMode.value) {
    uni.$emit('addressSelected', a)
    uni.navigateBack()
  } else {
    edit(a)
  }
}

function edit(a) {
  uni.navigateTo({ url: `/pages/address/edit${a ? '?id=' + a.id : ''}` })
}

async function setDefault(a) {
  try {
    await request.put(`/user/address/${a.id}/default`)
    uni.showToast({ title: '已设为默认', icon: 'success' })
    await load()
  } catch (e) { /* 已提示 */ }
}

async function remove(a) {
  const res = await uni.showModal({ title: '提示', content: '确认删除该地址？' })
  if (!res.confirm) return
  try {
    await request.delete(`/user/address/${a.id}`)
    uni.showToast({ title: '已删除', icon: 'success' })
    await load()
  } catch (e) { /* 已提示 */ }
}
</script>

<style lang="scss" scoped>
.address-page {
  padding: $gap $gap 180rpx;
}
.addr-card {
  background: $bg-card;
  border-radius: $radius;
  padding: 26rpx;
  margin-bottom: $gap;
  box-shadow: $shadow-card;
}
.addr-line1 {
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
  color: $text-2;
  line-height: 1.6;
}
.addr-actions {
  display: flex;
  justify-content: flex-end;
  gap: 32rpx;
  margin-top: 20rpx;
  padding-top: 20rpx;
  border-top: 1rpx solid $border-line;
}
.addr-action {
  display: flex;
  align-items: center;
  gap: 8rpx;
  font-size: $fs-note;
  color: $text-2;
}
.empty {
  padding: 100rpx 0;
}
.add-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  background: $bg-card;
  padding: 16rpx $gap calc(16rpx + env(safe-area-inset-bottom));
  box-shadow: $shadow-float;
}
.add-btn {
  background: $brand-grad;
  color: #fff;
  text-align: center;
  padding: 24rpx 0;
  border-radius: $radius-pill;
  font-size: 30rpx;
  box-shadow: $shadow-brand;
}
</style>
