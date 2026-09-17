<template>
  <div class="line-chart" :style="{ height: height + 'px' }">
    <svg :viewBox="`0 0 ${W} ${H}`" preserveAspectRatio="none" width="100%" height="100%" role="img">
      <defs>
        <linearGradient :id="gradId" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" :stop-color="color" stop-opacity="0.22" />
          <stop offset="100%" :stop-color="color" stop-opacity="0" />
        </linearGradient>
      </defs>

      <!-- 横向网格线 + 左侧数值刻度 -->
      <g v-for="(g, i) in gridYs" :key="i">
        <line :x1="padL" :x2="W - padR" :y1="g.y" :y2="g.y" stroke="#edf1f7" stroke-width="1" />
        <text :x="padL - 8" :y="g.y + 4" text-anchor="end" class="axis-tick">{{ fmt(g.value) }}</text>
      </g>

      <!-- 面积 + 折线 -->
      <path v-if="path.d" :d="path.area" :fill="`url(#${gradId})`" stroke="none" />
      <path v-if="path.d" :d="path.d" fill="none" :stroke="color" stroke-width="2.5"
            stroke-linejoin="round" stroke-linecap="round" />

      <!-- 数据点 -->
      <g v-for="(p, i) in pts" :key="'p' + i">
        <circle :cx="p.x" :cy="p.y" r="3.5" fill="#fff" :stroke="color" stroke-width="2">
          <title>{{ p.label }}：{{ fmt(p.value) }}</title>
        </circle>
      </g>

      <!-- X 轴标签（抽取首末与中间，避免拥挤） -->
      <text v-for="t in xTicks" :key="'x' + t.i" :x="t.x" :y="H - 8" text-anchor="middle" class="axis-tick">
        {{ t.label }}
      </text>

      <!-- 空数据 -->
      <text v-if="!pts.length" :x="W / 2" :y="H / 2" text-anchor="middle" class="axis-empty">暂无数据</text>
    </svg>
  </div>
</template>

<script setup>
import { computed } from 'vue'

// 极简 SVG 折线图：为零依赖、控制体积而未引入 echarts，足够满足“简单趋势”场景
const props = defineProps({
  points: { type: Array, default: () => [] },   // [{ label, value }]
  height: { type: Number, default: 240 },
  color: { type: String, default: '#3b82f6' }
})

const W = 640
const H = 240
const padL = 46
const padR = 16
const padT = 16
const padB = 28
const gradId = 'lg' + Math.random().toString(36).slice(2, 8)

const maxV = computed(() => Math.max(1, ...props.points.map((p) => p.value || 0)))

const plotW = W - padL - padR
const plotH = H - padT - padB

const pts = computed(() => {
  const n = props.points.length
  if (!n) return []
  return props.points.map((p, i) => ({
    label: p.label,
    value: p.value || 0,
    x: padL + (n === 1 ? plotW / 2 : (i * plotW) / (n - 1)),
    y: padT + plotH - ((p.value || 0) / maxV.value) * plotH
  }))
})

const path = computed(() => {
  const arr = pts.value
  if (!arr.length) return { d: '', area: '' }
  const line = arr.map((p, i) => (i === 0 ? `M${p.x},${p.y}` : `L${p.x},${p.y}`)).join(' ')
  const area = `${line} L${arr[arr.length - 1].x},${padT + plotH} L${arr[0].x},${padT + plotH} Z`
  return { d: line, area }
})

const gridYs = computed(() => {
  const ticks = 4
  return Array.from({ length: ticks + 1 }, (_, i) => {
    const v = (maxV.value / ticks) * i
    return { y: padT + plotH - (i / ticks) * plotH, value: v }
  })
})

const xTicks = computed(() => {
  const arr = pts.value
  const n = arr.length
  if (!n) return []
  const idx = [...new Set([0, Math.floor((n - 1) / 2), n - 1])]
  return idx.map((i) => ({ i, x: arr[i].x, label: arr[i].label }))
})

const fmt = (v) => (v >= 10000 ? (v / 10000).toFixed(v >= 100000 ? 0 : 1) + 'w' : Math.round(v * 100) / 100)
</script>

<style scoped>
.line-chart { width: 100%; }
.axis-tick { font-size: 11px; fill: #94a3b3; }
.axis-empty { font-size: 13px; fill: #94a3b3; }
</style>
