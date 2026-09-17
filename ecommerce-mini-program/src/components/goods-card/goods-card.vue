<template>
  <!-- 商品卡片（首页/分类/列表页复用） -->
  <view class="goods-card" @click="goDetail">
    <image class="goods-img" :src="goods.icon || defaultImg" mode="aspectFill" />
    <view class="goods-info">
      <view class="goods-name">{{ goods.name }}</view>
      <view class="goods-price-row">
        <view class="goods-price">
          <text class="goods-price-symbol">￥</text>{{ formatPrice(goods.price) }}
        </view>
        <text class="goods-stock" v-if="showStock">库存{{ goods.stock }}</text>
      </view>
    </view>
  </view>
</template>

<script setup>
import { formatPrice } from '../../utils/common'

// 必须接住返回值：<script setup> 的 props 只注入模板渲染上下文，
// 不会在脚本作用域生成同名变量。此前裸用 goods.id 会让点击卡片抛 ReferenceError。
const props = defineProps({
  goods: { type: Object, required: true },
  showStock: { type: Boolean, default: false }
})

const defaultImg = '/static/placeholder.png'

function goDetail() {
  uni.navigateTo({ url: `/pages/product/detail?id=${props.goods.id}` })
}
</script>

<style lang="scss" scoped>
.goods-card {
  background: $bg-card;
  border-radius: $radius;
  overflow: hidden;
  margin-bottom: $gap-sm;
  box-shadow: $shadow-card;
}
.goods-img {
  width: 100%;
  height: 340rpx;
  background: $bg-muted;
  display: block;
}
.goods-info {
  padding: 18rpx 20rpx 22rpx;
}
.goods-name {
  font-size: $fs-body;
  color: $text-1;
  line-height: 1.4;
  @include ellipsis(2);
  min-height: 78rpx;
}
.goods-price-row {
  margin-top: 14rpx;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.goods-price {
  color: $price;
  font-size: 34rpx;
  font-weight: 700;
  letter-spacing: -0.5rpx;
}
.goods-price-symbol {
  font-size: $fs-note;
  font-weight: 600;
  margin-right: 2rpx;
}
.goods-stock {
  font-size: $fs-mini;
  color: $text-3;
  background: $bg-muted;
  padding: 2rpx 10rpx;
  border-radius: $radius-pill;
}
</style>
