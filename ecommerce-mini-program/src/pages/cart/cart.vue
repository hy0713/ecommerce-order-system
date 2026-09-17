<template>
  <view class="cart-page">
    <!-- 未登录 -->
    <view class="need-login" v-if="!isLogin">
      <u-empty text="登录后查看购物车" mode="list">
        <template #bottom>
          <u-button type="primary" text="去登录" @click="goLogin"></u-button>
        </template>
      </u-empty>
    </view>

    <!-- 空购物车 -->
    <view class="empty" v-else-if="!items.length && !loading">
      <u-empty text="购物车空空如也" mode="car"></u-empty>
    </view>

    <template v-else>
      <!-- 购物车列表 -->
      <view class="cart-list">
        <view class="cart-item" v-for="item in items" :key="item.id">
          <!-- 选中：自绘控件。u-checkbox 的 isChecked 只在挂载时初始化，且组内按 name 匹配，
               本页既没给 name 也没给 group 绑 v-model，会导致初始状态恒为 false、并与服务端漂移 -->
          <view class="check-wrap" @click.stop="toggleSelect(item)">
            <view class="check-box" :class="{ checked: item.selected === 1 }">
              <u-icon v-if="item.selected === 1" name="checkmark" color="#ffffff" size="12"></u-icon>
            </view>
          </view>
          <!-- 商品信息 -->
          <image class="item-img" :src="item.productIcon || '/static/placeholder.png'" mode="aspectFill" @click="goDetail(item)" />
          <view class="item-info" @click="goDetail(item)">
            <view class="item-name">{{ item.productName }}</view>
            <view class="item-remark" v-if="item.invalid">已下架或库存不足</view>
            <view class="item-bottom">
              <text class="item-price">￥{{ formatPrice(item.productPrice) }}</text>
              <!-- 数量加减 -->
              <view class="stepper">
                <view class="step-btn" @click.stop="changeQuantity(item, -1)">-</view>
                <view class="step-num">{{ item.quantity }}</view>
                <view class="step-btn" @click.stop="changeQuantity(item, 1)">+</view>
              </view>
            </view>
          </view>
          <!-- 删除 -->
          <view class="del-btn" @click="removeItem(item)">
            <u-icon name="trash" color="#fa3534" size="22"></u-icon>
          </view>
        </view>
      </view>

      <!-- 底部结算栏 -->
      <view class="settle-bar">
        <view class="check-wrap" @click="toggleSelectAll">
          <view class="check-box" :class="{ checked: allSelected }">
            <u-icon v-if="allSelected" name="checkmark" color="#ffffff" size="12"></u-icon>
          </view>
          <text class="check-label">全选</text>
        </view>
        <view class="settle-total">
          <text class="total-label">合计：</text>
          <text class="total-price">￥{{ formatPrice(cartStore.selectedTotal) }}</text>
        </view>
        <view class="settle-btn" :class="{ disabled: cartStore.selectedCount === 0 }" @click="checkout">
          结算({{ cartStore.selectedCount }})
        </view>
      </view>
    </template>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app'
import { storeToRefs } from 'pinia'
import request from '../../utils/request'
import { formatPrice, checkLogin } from '../../utils/common'
import { useUserStore } from '../../store/user'
import { useCartStore } from '../../store/cart'

const userStore = useUserStore()
const cartStore = useCartStore()
const { isLogin } = storeToRefs(userStore)
const { items } = storeToRefs(cartStore)
const loading = ref(false)

const allSelected = computed(
  () => items.value.length > 0 && items.value.every((i) => i.selected === 1)
)

onShow(async () => {
  if (!userStore.isLogin) return
  await refresh()
})

async function refresh() {
  loading.value = true
  try {
    await cartStore.refresh()
  } catch (e) { /* 已提示 */ } finally {
    loading.value = false
  }
}

function goLogin() {
  uni.navigateTo({ url: '/pages/login/login' })
}

function goDetail(item) {
  uni.navigateTo({ url: `/pages/product/detail?id=${item.productId}` })
}

// 单条目勾选：调精确到条目的接口，不再拿「全选」接口模拟
// （旧实现用 every() 判断后下发全选，选一个未选商品会变成「全部取消选中」，逻辑上不可能正确）
async function toggleSelect(item) {
  try {
    // 第 3 参是「扁平的 query 对象」，不能写成 axios 风格的 { params: {...} }：
    // 封装会把整个对象当参数序列化，拼出 ?params=[object Object]，后端取不到 selected 而报「参数错误」
    await request.put(`/cart/${item.id}/selected`, null, { selected: item.selected !== 1 })
    await cartStore.refresh().catch(() => {})
  } catch (e) { /* 已提示 */ }
}

async function toggleSelectAll() {
  try {
    await request.put('/cart/select-all', null, { selected: !allSelected.value })
    await cartStore.refresh().catch(() => {})
  } catch (e) { /* 已提示 */ }
}

// 数量加减：实时同步后端，上限=库存
async function changeQuantity(item, delta) {
  const next = item.quantity + delta
  if (next < 1) return
  if (next > item.stock) {
    uni.showToast({ title: '不能超过库存', icon: 'none' })
    return
  }
  try {
    await request.put(`/cart/${item.id}/quantity`, { quantity: next })
    await cartStore.refresh().catch(() => {})
  } catch (e) { /* 已提示 */ }
}

async function removeItem(item) {
  const res = await uni.showModal({ title: '提示', content: '确认删除该商品？' })
  if (!res.confirm) return
  try {
    await request.delete(`/cart/${item.id}`)
    await cartStore.refresh().catch(() => {})
  } catch (e) { /* 已提示 */ }
}

function checkout() {
  if (cartStore.selectedCount === 0) {
    uni.showToast({ title: '请先选择商品', icon: 'none' })
    return
  }
  uni.navigateTo({ url: '/pages/order/confirm' })
}

onPullDownRefresh(async () => {
  if (userStore.isLogin) await refresh()
  uni.stopPullDownRefresh()
})
</script>

<style lang="scss" scoped>
.cart-page {
  padding: $gap $gap 180rpx;
}
.need-login, .empty {
  padding: 120rpx 0;
}
.cart-list {
  background: $bg-card;
  border-radius: $radius;
  box-shadow: $shadow-card;
  overflow: hidden;
}
.cart-item {
  display: flex;
  align-items: center;
  padding: 26rpx 24rpx;
  border-bottom: 1rpx solid $border-line;
}
.cart-item:last-child {
  border-bottom: none;
}
/* 自绘勾选控件 */
.check-wrap {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  padding-right: 8rpx;
}
.check-box {
  width: 40rpx;
  height: 40rpx;
  border-radius: 50%;
  border: 2rpx solid #d1d5db;
  background: $bg-card;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
}
.check-box.checked {
  background: $brand;
  border-color: $brand;
}
.check-label {
  margin-left: 14rpx;
  font-size: $fs-note;
  color: $text-2;
}
.item-img {
  width: 160rpx;
  height: 160rpx;
  border-radius: $radius-sm;
  background: $bg-muted;
  margin: 0 20rpx;
  flex-shrink: 0;
}
.item-info {
  flex: 1;
  min-width: 0;
}
.item-name {
  font-size: $fs-body;
  color: $text-1;
  line-height: 1.4;
  @include ellipsis(1);
}
.item-remark {
  font-size: $fs-mini;
  color: $danger;
  margin-top: 6rpx;
}
.item-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 18rpx;
}
.item-price {
  color: $price;
  font-size: 32rpx;
  font-weight: 700;
}
.stepper {
  display: flex;
  align-items: center;
  border-radius: $radius-sm;
  overflow: hidden;
  background: $bg-muted;
}
.step-btn {
  width: 52rpx;
  height: 52rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 32rpx;
  color: $text-2;
}
.step-num {
  width: 68rpx;
  text-align: center;
  font-size: 26rpx;
  color: $text-1;
  background: $bg-card;
  height: 52rpx;
  line-height: 52rpx;
}
.del-btn {
  margin-left: 16rpx;
  padding: 16rpx;
}
.settle-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  background: $bg-card;
  padding: 16rpx $gap calc(16rpx + env(safe-area-inset-bottom));
  display: flex;
  align-items: center;
  box-shadow: $shadow-float;
  z-index: 10;
}
.settle-total {
  flex: 1;
  text-align: right;
  margin-right: 20rpx;
}
.total-label {
  font-size: $fs-note;
  color: $text-2;
}
.total-price {
  font-size: 36rpx;
  color: $price;
  font-weight: 700;
}
.settle-btn {
  background: $brand-grad;
  color: #fff;
  font-size: $fs-body;
  padding: 18rpx 40rpx;
  border-radius: $radius-pill;
  box-shadow: $shadow-brand;
}
.settle-btn.disabled {
  opacity: 0.45;
  box-shadow: none;
}
</style>
