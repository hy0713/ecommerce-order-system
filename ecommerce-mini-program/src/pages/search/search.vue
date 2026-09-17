<template>
  <view class="search-page">
    <!-- 搜索栏：进入即聚焦，回车/点击「搜索」触发 -->
    <view class="search-bar">
      <view class="input-wrap">
        <u-icon name="search" color="#9ca3af" size="18"></u-icon>
        <input
          class="input"
          v-model="keyword"
          type="text"
          :focus="autoFocus"
          confirm-type="search"
          placeholder="搜索商品名称"
          placeholder-class="ph"
          @confirm="onSearch"
        />
        <view class="clear-btn" v-if="keyword" @click="clearKeyword">
          <u-icon name="close-circle-fill" color="#c0c4cc" size="17"></u-icon>
        </view>
      </view>
      <text class="action" @click="onSearch">搜索</text>
    </view>

    <!-- ① 未搜索态：只给历史与热门，不铺商品（否则与首页没有区别） -->
    <block v-if="!searched">
      <view class="panel" v-if="history.length">
        <view class="panel-head">
          <text class="panel-title">搜索历史</text>
          <view class="panel-action" @click="clearHistory">
            <u-icon name="trash" color="#9ca3af" size="15"></u-icon>
            <text>清空</text>
          </view>
        </view>
        <view class="tags">
          <view class="tag" v-for="(w, i) in history" :key="'h' + i" @click="searchWord(w)">{{ w }}</view>
        </view>
      </view>

      <view class="panel">
        <view class="panel-head">
          <text class="panel-title">热门搜索</text>
        </view>
        <view class="tags">
          <view class="tag hot" v-for="(w, i) in hotWords" :key="'g' + i" @click="searchWord(w)">{{ w }}</view>
        </view>
      </view>

      <view class="hint">
        <u-icon name="search" color="#c0c4cc" size="28"></u-icon>
        <text>输入商品名称开始搜索</text>
      </view>
    </block>

    <!-- ② 结果态 -->
    <block v-else>
      <view class="result-tip" v-if="!loading">
        <text v-if="goods.length">找到 {{ total }} 件相关商品</text>
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
        <u-empty mode="search" :text="`没有找到「${searchedWord}」相关商品`"></u-empty>
        <view class="empty-tip">换个关键词试试，或看看下面的推荐</view>
        <view class="tags center">
          <view class="tag hot" v-for="(w, i) in hotWords.slice(0, 4)" :key="'e' + i" @click="searchWord(w)">{{ w }}</view>
        </view>
      </view>

      <view class="load-more">{{ loading ? '搜索中...' : (finished && goods.length ? '没有更多了' : '') }}</view>
    </block>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { onLoad, onReachBottom } from '@dcloudio/uni-app'
import request from '../../utils/request'

const HISTORY_KEY = 'searchHistory'
const HISTORY_MAX = 10
// 兜底热门词：均已实测能命中在售商品（keyword 匹配的是商品名称）
const FALLBACK_HOT = ['iPhone', 'Mate', 'AirPods', 'MacBook', '键盘', '空调']

const keyword = ref('')          // 输入框内容
const searchedWord = ref('')     // 已提交搜索的词（用于空态文案）
const searched = ref(false)      // 是否已发起过搜索 —— 决定渲染「初始态」还是「结果态」
const autoFocus = ref(false)
const history = ref([])
const hotWords = ref([...FALLBACK_HOT])
const goods = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = 10
const loading = ref(false)
const finished = ref(false)

const leftCol = computed(() => goods.value.filter((_, i) => i % 2 === 0))
const rightCol = computed(() => goods.value.filter((_, i) => i % 2 === 1))

onLoad(async (options) => {
  history.value = uni.getStorageSync(HISTORY_KEY) || []
  if (options.keyword) {
    // 外部直接带词进来（如列表页透传），直接出结果
    keyword.value = decodeURIComponent(options.keyword)
    onSearch()
  } else {
    // 未带词：聚焦输入框，等着用户输
    setTimeout(() => { autoFocus.value = true }, 200)
  }
  loadHotWords()
})

// 热门词从真实在售商品名里提取 —— 数据变了也不会失效（写死的关键词迟早搜不到东西）
async function loadHotWords() {
  try {
    const data = await request.get('/product/page', { pageNum: 1, pageSize: 10, status: 1 })
    const words = []
    for (const p of data.records || []) {
      // 取商品名首个词片段：'iPhone 15 Pro' → 'iPhone'、'AirPods Pro 2' → 'AirPods'
      const w = String(p.name || '').trim().split(/\s+/)[0]
      if (w && !words.includes(w)) words.push(w)
      if (words.length >= 6) break
    }
    if (words.length) hotWords.value = words
  } catch (e) { /* 用兜底词，不打断页面 */ }
}

function clearKeyword() {
  keyword.value = ''
}

// 提交搜索
function onSearch() {
  const kw = keyword.value.trim()
  if (!kw) {
    uni.showToast({ title: '请输入搜索内容', icon: 'none' })
    return
  }
  searchedWord.value = kw
  saveHistory(kw)
  loadGoods(true)
}

// 点标签：填入输入框并立即搜索
function searchWord(w) {
  keyword.value = w
  onSearch()
}

function saveHistory(w) {
  const list = [w, ...history.value.filter((x) => x !== w)].slice(0, HISTORY_MAX)
  history.value = list
  uni.setStorageSync(HISTORY_KEY, list)
}

function clearHistory() {
  uni.showModal({
    title: '提示',
    content: '确定清空搜索历史？',
    success: (res) => {
      if (!res.confirm) return
      history.value = []
      uni.removeStorageSync(HISTORY_KEY)
    }
  })
}

// 请求序号：新搜索要能打断在途请求，否则「点了没反应」
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
      keyword: searchedWord.value
    })
    if (seq !== reqSeq) return
    searched.value = true
    const records = data.records || []
    goods.value = reset ? records : goods.value.concat(records)
    total.value = Number(data.total || 0)
    pageNum.value = reset ? 2 : pageNum.value + 1
    finished.value = records.length < pageSize
  } catch (e) { /* 已提示 */ } finally {
    if (seq === reqSeq) loading.value = false
  }
}

onReachBottom(() => {
  if (searched.value) loadGoods(false)
})
</script>

<style lang="scss" scoped>
.search-page {
  padding: $gap $gap 40rpx;
  min-height: 100vh;
  background: $bg-page;
}
.search-bar {
  display: flex;
  align-items: center;
  gap: 20rpx;
  margin-bottom: $gap;
}
.input-wrap {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 12rpx;
  background: $bg-card;
  border-radius: $radius-pill;
  padding: 16rpx 26rpx;
  box-shadow: $shadow-card;
}
.input {
  flex: 1;
  min-width: 0;
  font-size: $fs-body;
  color: $text-1;
}
.ph {
  color: $text-3;
  font-size: $fs-body;
}
.clear-btn {
  display: flex;
  align-items: center;
}
.action {
  font-size: $fs-body;
  color: $brand;
  font-weight: 600;
  flex-shrink: 0;
}
.panel {
  background: $bg-card;
  border-radius: $radius;
  padding: 26rpx;
  margin-bottom: $gap;
  box-shadow: $shadow-card;
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20rpx;
}
.panel-title {
  font-size: 28rpx;
  font-weight: 600;
  color: $text-1;
}
.panel-action {
  display: flex;
  align-items: center;
  gap: 6rpx;
  font-size: 24rpx;
  color: $text-3;
}
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 18rpx;
}
.tags.center {
  justify-content: center;
  margin-top: 24rpx;
}
.tag {
  padding: 12rpx 26rpx;
  background: $bg-muted;
  border-radius: $radius-pill;
  font-size: 26rpx;
  color: $text-2;
}
.tag.hot {
  background: rgba(37, 99, 235, 0.08);
  color: $brand;
}
.hint {
  margin-top: 120rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 20rpx;
  color: $text-3;
  font-size: $fs-note;
}
.result-tip {
  padding: 0 4rpx 20rpx;
  font-size: $fs-note;
  color: $text-3;
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
  padding: 100rpx 0 0;
}
.empty-tip {
  text-align: center;
  color: $text-3;
  font-size: $fs-note;
  margin-top: 16rpx;
}
.load-more {
  text-align: center;
  color: $text-3;
  font-size: $fs-note;
  padding: 24rpx 0;
}
</style>
