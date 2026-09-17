<template>
  <view class="category-page">
    <!-- 左侧一级分类导航 -->
    <scroll-view class="left-nav" scroll-y>
      <view
        v-for="c in categories"
        :key="c.id"
        class="nav-item"
        :class="{ active: c.id === activeId }"
        @click="switchCategory(c)"
      >{{ c.name }}</view>
    </scroll-view>

    <!-- 右侧商品列表 -->
    <view class="right-goods">
      <view class="goods-grid" v-if="goods.length">
        <view class="grid-col">
          <goods-card v-for="g in leftCol" :key="g.id" :goods="g" />
        </view>
        <view class="grid-col">
          <goods-card v-for="g in rightCol" :key="g.id" :goods="g" />
        </view>
      </view>
      <view class="empty" v-else-if="!loading">
        <u-empty text="该分类暂无在售商品" mode="list"></u-empty>
      </view>
      <view class="load-more">{{ loading ? '加载中...' : (finished ? '没有更多了' : '') }}</view>
    </view>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { onLoad, onReachBottom } from '@dcloudio/uni-app'
import request from '../../utils/request'

const categories = ref([])
const activeId = ref(null)
const goods = ref([])
const pageNum = ref(1)
const pageSize = 10
const loading = ref(false)
const finished = ref(false)

const leftCol = computed(() => goods.value.filter((_, i) => i % 2 === 0))
const rightCol = computed(() => goods.value.filter((_, i) => i % 2 === 1))

onLoad(async () => {
  try {
    const data = await request.get('/category/tree')
    categories.value = (data || []).map((c) => ({ id: c.id, name: c.name }))
    if (categories.value.length) {
      activeId.value = categories.value[0].id
      await loadGoods(true)
    }
  } catch (e) { /* 已提示 */ }
})

async function switchCategory(c) {
  if (c.id === activeId.value) return
  activeId.value = c.id
  // reset 分支必须能打断在途请求，否则切分类只换了高亮、商品还是旧分类的
  await loadGoods(true)
}

// 请求序号：用于丢弃「已被更新请求取代」的慢响应
let reqSeq = 0

async function loadGoods(reset) {
  if (!activeId.value) return
  if (!reset && (loading.value || finished.value)) return
  const seq = ++reqSeq
  const categoryId = activeId.value
  loading.value = true
  try {
    const data = await request.get('/product/page', {
      pageNum: reset ? 1 : pageNum.value,
      pageSize,
      categoryId,
      status: 1
    })
    if (seq !== reqSeq) return   // 分类已再次切换，丢弃本次结果
    const records = data.records || []
    goods.value = reset ? records : goods.value.concat(records)
    pageNum.value = reset ? 2 : pageNum.value + 1
    finished.value = records.length < pageSize
  } catch (e) { /* 已提示 */ } finally {
    if (seq === reqSeq) loading.value = false
  }
}

onReachBottom(() => loadGoods(false))
</script>

<style lang="scss" scoped>
.category-page {
  display: flex;
  height: 100vh;
  background: $bg-page;
}
.left-nav {
  width: 190rpx;
  height: 100%;
  background: $bg-page;
}
.nav-item {
  padding: 30rpx 20rpx;
  font-size: $fs-body;
  color: $text-2;
  text-align: center;
  border-left: 6rpx solid transparent;
  transition: background 0.18s, color 0.18s;
}
.nav-item.active {
  background: $bg-card;
  color: $brand;
  font-weight: 600;
  border-left-color: $brand;
}
.right-goods {
  flex: 1;
  padding: $gap;
  background: $bg-page;
  height: 100%;
  overflow-y: auto;
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
