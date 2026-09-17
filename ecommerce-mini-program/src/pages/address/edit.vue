<template>
  <view class="edit-page">
    <view class="form-card">
      <view class="form-item">
        <text class="form-label">收货人</text>
        <input class="form-input" v-model="form.receiverName" placeholder="请输入收货人姓名" maxlength="32" />
      </view>
      <view class="form-item">
        <text class="form-label">手机号</text>
        <input class="form-input" v-model="form.receiverPhone" type="number" placeholder="请输入手机号" maxlength="11" />
      </view>
      <view class="form-item">
        <text class="form-label">详细地址</text>
        <input class="form-input" v-model="form.address" placeholder="请输入详细收货地址" maxlength="255" />
      </view>
      <view class="form-item switch-item">
        <text class="form-label">设为默认地址</text>
        <u-switch v-model="form.isDefault" activeColor="#2979ff" :activeValue="1" :inactiveValue="0"></u-switch>
      </view>
    </view>

    <view class="save-btn" @click="save">保 存</view>
  </view>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import request from '../../utils/request'

const isEdit = ref(false)
const form = reactive({ id: null, receiverName: '', receiverPhone: '', address: '', isDefault: 0 })

onLoad(async (options) => {
  if (options.id) {
    isEdit.value = true
    uni.setNavigationBarTitle({ title: '编辑地址' })
    try {
      const list = await request.get('/user/address/list')
      const target = (list || []).find((a) => String(a.id) === String(options.id))
      if (target) {
        form.id = target.id
        form.receiverName = target.receiverName
        form.receiverPhone = target.receiverPhone
        form.address = target.address
        form.isDefault = target.isDefault
      }
    } catch (e) { /* 已提示 */ }
  }
})

async function save() {
  if (!form.receiverName) {
    uni.showToast({ title: '请输入收货人姓名', icon: 'none' })
    return
  }
  if (!/^1[3-9]\d{9}$/.test(form.receiverPhone)) {
    uni.showToast({ title: '手机号格式不正确', icon: 'none' })
    return
  }
  if (!form.address) {
    uni.showToast({ title: '请输入详细地址', icon: 'none' })
    return
  }
  const payload = {
    receiverName: form.receiverName,
    receiverPhone: form.receiverPhone,
    address: form.address,
    isDefault: form.isDefault
  }
  try {
    if (isEdit.value) {
      await request.put(`/user/address/${form.id}`, payload)
    } else {
      await request.post('/user/address', payload)
    }
    uni.showToast({ title: '保存成功', icon: 'success' })
    setTimeout(() => uni.navigateBack(), 500)
  } catch (e) { /* 已提示 */ }
}
</script>

<style lang="scss" scoped>
.edit-page {
  padding: $gap;
}
.form-card {
  background: $bg-card;
  border-radius: $radius;
  padding: 0 26rpx;
  box-shadow: $shadow-card;
}
.form-item {
  display: flex;
  align-items: center;
  padding: 30rpx 0;
  border-bottom: 1rpx solid $border-line;
}
.form-item:last-child {
  border-bottom: none;
}
.form-label {
  width: 200rpx;
  font-size: $fs-body;
  color: $text-1;
}
.form-input {
  flex: 1;
  font-size: $fs-body;
  color: $text-1;
}
.switch-item {
  justify-content: space-between;
}
.save-btn {
  margin-top: 44rpx;
  background: $brand-grad;
  color: #fff;
  text-align: center;
  padding: 26rpx 0;
  border-radius: $radius-pill;
  font-size: 32rpx;
  box-shadow: $shadow-brand;
}
</style>
