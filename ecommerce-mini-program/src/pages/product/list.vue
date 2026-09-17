<template>
  <view class="list-page">
    <!-- 搜索入口：只做导航，真正的搜索在独立搜索页里完成（避免本页既当分类列表又当搜索页） -->
    <view class="search-bar" @click="goSearch">
      <u-icon name="search" color="#9ca3af" size="19"></u-icon>
      <text class="search-placeholder">搜索商品名称</text>
    </view>

    <!-- 当前分类过滤（来自首页分类入口 / 分类页），可一键清除 -->
    <view class="filter-chip" v-if="categoryName">
      <text class="chip-label">分类：{{ categoryName }}</text>
      <view class="chip-close" @click="clearCategory">
        <u-icon name="close" size="12" color="#2563eb"></u-icon>
      </view>
    </view>

    <!-- 商品双列列表 -->
    <view class="goods-grid" v-if="goods.length">
      <view class="grid-col">
        <goods-card v-for="g in leftCol" :key="g.id" :goods="g" />
      </view>
      <view class="grid-col">
        <goods-card v-for="g in rightCol" :key="g.id" :goods="g" />
      </view>
    </view>
    <view class="empty" v-else-if="!loading">
      <u-empty :text="categoryName ? `「${categoryName}」暂无在售商品` : '暂无相关商品'" mode="list"></u-empty>
    </view>
    <view class="load-more">{{ loading ? '加载中...' : (finished ? '没有更多了' : '') }}</view>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onReachBottom } from '@dcloudio/uni-app'
import request from '../../utils/request'

const categoryId = ref(null)
const categoryName = ref('')
const goods = ref([])
const pageNum = ref(1)
const pageSize = 10
const loading = ref(false)
const finished = ref(false)

const leftCol = computed(() => goods.value.filter((_, i) => i % 2 === 0))
const rightCol = computed(() => goods.value.filter((_, i) => i % 2 === 1))

onLoad(async (options) => {
  // 兼容老入口：外部若仍传 keyword，转到独立搜索页去执行，本页只负责分类浏览
  if (options.keyword) {
    uni.redirectTo({ url: `/pages/search/search?keyword=${encodeURIComponent(decodeURIComponent(options.keyword))}` })
    return
  }
  if (options.categoryId) {
    categoryId.value = options.categoryId
  }
  if (options.name) {
    categoryName.value = decodeURIComponent(options.name)
    uni.setNavigationBarTitle({ title: categoryName.value })
  }
  await loadGoods(true)
})

function goSearch() {
  uni.navigateTo({ url: '/pages/search/search' })
}

// 一键清除分类过滤，回到全量商品
function clearCategory() {
  categoryId.value = null
  categoryName.value = ''
  uni.setNavigationBarTitle({ title: '商品列表' })
  loadGoods(true)
}

// 请求序号：用于丢弃「已被更新请求取代」的慢响应
// （reset 分支不能被在途请求挡掉，否则切分类点了没反应）
let reqSeq = 0

async function loadGoods(reset) {
  if (!reset && (loading.value || finished.value)) return
  const seq = ++reqSeq
  loading.value = true
  try {
    const data = await request.get('/product/page', {
      pageNum: reset ? 1 : pageNum.value,
      pageSize,
      status: 1,
      ...(categoryId.value ? { categoryId: categoryId.value } : {})
    })
    if (seq !== reqSeq) return   // 已有更新的请求，丢弃本次结果
    const records = data.records || []
    goods.value = reset ? records : goods.value.concat(records)
    pageNum.value = reset ? 2 : pageNum.value + 1
    finished.value = records.length < pageSize
  } catch (e) { /* 已提示 */ } finally {
    if (seq === reqSeq) loading.value = false
  }
}

onPullDownRefresh(async () => {
  await loadGoods(true)
  uni.stopPullDownRefresh()
})

onReachBottom(() => loadGoods(false))
</script>

<style lang="scss" scoped>
.list-page {
  padding: $gap $gap 40rpx;
  min-height: 100vh;
}
/* 搜索入口视觉与首页一致，但只是导航（真正搜索在 pages/search/search） */
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
.filter-chip {
  display: inline-flex;
  align-items: center;
  gap: 10rpx;
  padding: 8rpx 20rpx;
  margin-bottom: $gap;
  background: rgba(37, 99, 235, 0.08);
  border-radius: $radius-pill;
}
.chip-label {
  font-size: 24rpx;
  color: $brand;
}
.chip-close {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32rpx;
  height: 32rpx;
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
  padding: 100rpx 0;
}
.load-more {
  text-align: center;
  color: $text-3;
  font-size: $fs-note;
  padding: 20rpx 0;
}
</style>
