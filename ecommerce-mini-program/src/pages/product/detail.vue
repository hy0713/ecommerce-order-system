<template>
  <view class="detail-page" v-if="product">
    <!-- 商品图片 -->
    <swiper class="banner" indicator-dots autoplay circular>
      <swiper-item v-for="(img, i) in images" :key="i">
        <image class="banner-img" :src="img" mode="aspectFill" />
      </swiper-item>
    </swiper>

    <!-- 信息区 -->
    <view class="info-card">
      <view class="price-row">
        <text class="price"><text class="price-symbol">￥</text>{{ formatPrice(product.price) }}</text>
        <text class="stock">库存 {{ product.stock }}</text>
      </view>
      <view class="name">{{ product.name }}</view>
      <view class="category" v-if="product.categoryName">分类：{{ product.categoryName }}</view>
    </view>

    <!-- 商品描述 -->
    <view class="desc-card">
      <view class="desc-title">商品描述</view>
      <view class="desc-content">{{ product.description || '暂无描述' }}</view>
    </view>

    <!-- 底部操作栏 -->
    <view class="bottom-bar">
      <view class="bar-btn cart-btn" @click="addToCart">加入购物车</view>
      <view class="bar-btn buy-btn" @click="buyNow">立即购买</view>
    </view>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import request from '../../utils/request'
import { formatPrice, checkLogin } from '../../utils/common'
import { useCartStore } from '../../store/cart'

const cartStore = useCartStore()
const product = ref(null)
const quantity = ref(1)

const images = computed(() => {
  const icon = product.value.icon
  return icon ? [icon] : ['/static/placeholder.png']
})

onLoad(async (options) => {
  if (!options.id) {
    uni.showToast({ title: '参数错误', icon: 'none' })
    return
  }
  try {
    // 详情接口走 Redis 缓存（/api/product/{id}）
    product.value = await request.get(`/product/${options.id}`)
  } catch (e) { /* 已提示 */ }
})

// 加入购物车：需登录
async function addToCart() {
  if (!checkLogin()) return
  try {
    await request.post('/cart', { productId: product.value.id, quantity: quantity.value })
    uni.showToast({ title: '已加入购物车', icon: 'success' })
    await cartStore.refresh().catch(() => {})
  } catch (e) { /* 已提示 */ }
}

// 立即购买：取消全选 → 加购当前商品（默认选中）→ 跳订单确认，保证只结算当前商品
async function buyNow() {
  if (!checkLogin()) return
  uni.showLoading({ title: '处理中' })
  try {
    // 取消全选后加购：新加购条目默认 selected=1，从而只结算当前商品。
    // 第 3 参必须是扁平 query 对象，写成 { params: {...} } 会拼成 ?params=[object Object] 触发「参数错误」
    await request.put('/cart/select-all', null, { selected: false })
    await request.post('/cart', { productId: product.value.id, quantity: quantity.value })
    uni.hideLoading()
    await cartStore.refresh().catch(() => {})
    uni.navigateTo({ url: '/pages/order/confirm' })
  } catch (e) {
    uni.hideLoading()
  }
}
</script>

<style lang="scss" scoped>
.detail-page {
  padding-bottom: 150rpx;
}
.banner {
  width: 100%;
  height: 750rpx;
}
.banner-img {
  width: 100%;
  height: 100%;
  background: $bg-muted;
}
.info-card {
  background: $bg-card;
  padding: 28rpx 30rpx;
  margin: $gap;
  border-radius: $radius;
  box-shadow: $shadow-card;
}
.price-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 14rpx;
}
.price {
  color: $price;
  font-size: 48rpx;
  font-weight: 700;
}
.price-symbol {
  font-size: 28rpx;
  font-weight: 600;
}
.stock {
  font-size: $fs-note;
  color: $text-3;
  background: $bg-muted;
  padding: 4rpx 14rpx;
  border-radius: $radius-pill;
}
.name {
  font-size: $fs-title;
  font-weight: 600;
  color: $text-1;
  line-height: 1.4;
}
.category {
  margin-top: 12rpx;
  font-size: $fs-note;
  color: $text-3;
}
.desc-card {
  background: $bg-card;
  margin: 0 $gap;
  padding: 28rpx 30rpx;
  border-radius: $radius;
  box-shadow: $shadow-card;
}
.desc-title {
  font-size: 30rpx;
  font-weight: 600;
  color: $text-1;
  margin-bottom: 16rpx;
  padding-left: 18rpx;
  position: relative;
}
.desc-title::before {
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
.desc-content {
  font-size: 26rpx;
  color: $text-2;
  line-height: 1.8;
}
.bottom-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  background: $bg-card;
  padding: 16rpx $gap calc(16rpx + env(safe-area-inset-bottom));
  display: flex;
  gap: 20rpx;
  box-shadow: $shadow-float;
}
.bar-btn {
  flex: 1;
  text-align: center;
  padding: 22rpx 0;
  border-radius: $radius-pill;
  font-size: 30rpx;
  color: #fff;
}
.cart-btn {
  background: linear-gradient(90deg, #f59e0b, #fbbf24);
  box-shadow: 0 8rpx 18rpx rgba(245, 158, 11, 0.28);
}
.buy-btn {
  background: $brand-grad;
  box-shadow: $shadow-brand;
}
</style>
