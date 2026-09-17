<template>
  <view class="index-page">
    <!-- 顶部搜索框 -->
    <view class="search-bar" @click="goSearch">
      <u-icon name="search" color="#9ca3af" size="19"></u-icon>
      <text class="search-placeholder">搜索商品名称</text>
    </view>

    <!-- 轮播图：取上架商品前 4 个，点击跳商品详情 -->
    <view class="banner-wrap">
      <u-swiper :list="banners" keyName="image" height="300" radius="16" @click="onBannerClick" />
    </view>

    <!-- 智能客服入口：Agent 是项目的独立模块，首页要给一个入口，不能只藏在「我的」里 -->
    <view class="agent-entry" @click="goAgent">
      <view class="agent-icon">AI</view>
      <view class="agent-info">
        <view class="agent-title">智能客服</view>
        <view class="agent-desc">商品库存 · 订单查询 · 售后政策，直接问</view>
      </view>
      <u-icon name="arrow-right" color="#2563eb" size="18"></u-icon>
    </view>

    <!-- 分类快捷入口 -->
    <view class="category-entry">
      <view class="category-item" v-for="c in categories" :key="c.id" @click="goCategory(c)">
        <view class="category-icon">{{ c.name.charAt(0) }}</view>
        <text class="category-name">{{ c.name }}</text>
      </view>
    </view>

    <!-- 热门商品双列 -->
    <view class="section-title">
      <text>热门商品</text>
    </view>
    <view class="goods-grid" v-if="goods.length">
      <view class="grid-col">
        <goods-card v-for="g in leftCol" :key="g.id" :goods="g" />
      </view>
      <view class="grid-col">
        <goods-card v-for="g in rightCol" :key="g.id" :goods="g" />
      </view>
    </view>
    <view class="empty" v-else-if="!loading">
      <u-empty text="暂无商品" mode="list"></u-empty>
    </view>
    <view class="load-more">{{ loading ? '加载中...' : (finished ? '没有更多了' : '') }}</view>
  </view>
</template>
<script setup>
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onReachBottom } from '@dcloudio/uni-app'
import request from '../../utils/request'
import { formatPrice } from '../../utils/common'

const banners = ref([])
const categories = ref([])
const goods = ref([])
const pageNum = ref(1)
const pageSize = 10
const loading = ref(false)
const finished = ref(false)

// 双列布局：奇数/偶数分列
const leftCol = computed(() => goods.value.filter((_, i) => i % 2 === 0))
const rightCol = computed(() => goods.value.filter((_, i) => i % 2 === 1))

onLoad(async () => {
  await Promise.all([loadCategories(), loadGoods(true)])
})

// 分类树（一级分类）
async function loadCategories() {
  try {
    const data = await request.get('/category/tree')
    categories.value = (data || []).map((c) => ({ id: c.id, name: c.name }))
  } catch (e) { /* 已提示 */ }
}

// 上架商品分页
async function loadGoods(reset) {
  if (loading.value || (finished.value && !reset)) return
  loading.value = true
  try {
    const data = await request.get('/product/page', {
      pageNum: reset ? 1 : pageNum.value,
      pageSize,
      status: 1
    })
    const records = data.records || []
    if (reset) {
      goods.value = records
      pageNum.value = 2
    } else {
      goods.value = goods.value.concat(records)
      pageNum.value++
    }
    // 轮播图取前 4 个有图标的商品
    if (reset) {
      banners.value = records.filter((p) => p.icon).slice(0, 4).map((p) => ({ image: p.icon, id: p.id }))
      if (banners.value.length < 3) {
        banners.value = records.slice(0, 4).map((p) => ({ image: p.icon || '/static/placeholder.png', id: p.id }))
      }
    }
    finished.value = records.length < pageSize
  } catch (e) { /* 已提示 */ } finally {
    loading.value = false
  }
}

function onBannerClick(index) {
  const item = banners.value[index]
  if (item && item.id) {
    uni.navigateTo({ url: `/pages/product/detail?id=${item.id}` })
  }
}

function goSearch() {
  // 跳独立搜索页：它是「先输入、后出结果」的形态，不会像列表页那样一进去就铺满商品
  uni.navigateTo({ url: '/pages/search/search' })
}

function goCategory(c) {
  // 带上分类名：列表页用它做导航栏标题与筛选提示，用户才知道自己看的是哪一类
  uni.navigateTo({ url: `/pages/product/list?categoryId=${c.id}&name=${encodeURIComponent(c.name)}` })
}

// 智能客服是 tabBar 页面，必须用 switchTab 跳转（navigateTo 无法打开 tabBar 页）
function goAgent() {
  uni.switchTab({ url: '/pages/agent/agent' })
}

onPullDownRefresh(async () => {
  await loadGoods(true)
  uni.stopPullDownRefresh()
})

onReachBottom(() => loadGoods(false))
</script>

<style lang="scss" scoped>
.index-page {
  padding: $gap $gap 40rpx;
}
.search-bar {
  background: $bg-card;
  border-radius: $radius-pill;
  padding: 20rpx 28rpx;
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-bottom: $gap;
  box-shadow: $shadow-card;
}
.search-placeholder {
  color: $text-3;
  font-size: $fs-body;
}
.banner-wrap {
  margin-bottom: $gap;
  border-radius: $radius-lg;
  overflow: hidden;
}
/* 智能客服入口 */
.agent-entry {
  display: flex;
  align-items: center;
  gap: 20rpx;
  background: $bg-card;
  border-radius: $radius;
  padding: 24rpx 26rpx;
  margin-bottom: $gap;
  box-shadow: $shadow-card;
}
.agent-icon {
  width: 76rpx;
  height: 76rpx;
  border-radius: 22rpx;
  background: $brand-grad;
  color: #fff;
  font-size: 30rpx;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  box-shadow: 0 6rpx 14rpx rgba(37, 99, 235, 0.22);
}
.agent-info {
  flex: 1;
  min-width: 0;
}
.agent-title {
  font-size: 30rpx;
  font-weight: 600;
  color: $text-1;
}
.agent-desc {
  font-size: 24rpx;
  color: $text-3;
  margin-top: 6rpx;
}
.category-entry {
  background: $bg-card;
  border-radius: $radius;
  padding: 28rpx 12rpx 8rpx;
  display: flex;
  flex-wrap: wrap;
  margin-bottom: $gap;
  box-shadow: $shadow-card;
}
.category-item {
  width: 25%;
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 20rpx;
}
.category-icon {
  width: 92rpx;
  height: 92rpx;
  border-radius: 28rpx;
  background: $brand-grad;
  color: #fff;
  font-size: 36rpx;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 12rpx;
  box-shadow: 0 6rpx 14rpx rgba(37, 99, 235, 0.22);
}
.category-name {
  font-size: $fs-note;
  color: $text-2;
}
.section-title {
  font-size: $fs-title;
  font-weight: 600;
  color: $text-1;
  margin: 8rpx 0 $gap;
  padding-left: 20rpx;
  position: relative;
}
.section-title::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 8rpx;
  height: 30rpx;
  border-radius: 4rpx;
  background: $brand-grad;
}
.goods-grid {
  display: flex;
  gap: $gap-sm;
}
.grid-col {
  flex: 1;
  min-width: 0;
}
.empty {
  padding: 80rpx 0;
}
.load-more {
  text-align: center;
  color: $text-3;
  font-size: $fs-note;
  padding: 20rpx 0;
}
</style>
