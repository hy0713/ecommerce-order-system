<template>
  <div class="donut-wrap" :style="{ height: size + 'px' }">
    <div class="donut-chart" :style="{ width: size + 'px', height: size + 'px' }">
      <svg :viewBox="`0 0 ${V} ${V}`" width="100%" height="100%" role="img">
        <!-- 底环 -->
        <circle :cx="c" :cy="c" :r="r" fill="none" stroke="#f1f5f9" :stroke-width="thick" />
        <!-- 各段 -->
        <circle
          v-for="(s, i) in segs" :key="i"
          :cx="c" :cy="c" :r="r" fill="none"
          :stroke="s.color" :stroke-width="thick" stroke-linecap="round"
          :stroke-dasharray="`${s.len} ${circ - s.len}`"
          :stroke-dashoffset="-s.offset"
          transform="rotate(-90 ${c} ${c})"
          class="seg"
        >
          <title>{{ s.label }}：{{ s.value }}</title>
        </circle>
        <!-- 中心合计 -->
        <text :x="c" :y="c - 4" text-anchor="middle" class="total num">{{ total }}</text>
        <text :x="c" :y="c + 18" text-anchor="middle" class="total-label">{{ centerLabel }}</text>
      </svg>
    </div>
    <!-- 图例 -->
    <ul class="legend">
      <li v-for="(s, i) in segs" :key="i">
        <i class="sw" :style="{ background: s.color }"></i>
        <span class="lb">{{ s.label }}</span>
        <span class="vl num">{{ s.value }}</span>
      </li>
      <li v-if="!segs.length" class="empty">暂无数据</li>
    </ul>
  </div>
</template>

<script setup>
import { computed } from 'vue'

// 极简 SVG 环形图：同样零依赖
const props = defineProps({
  segments: { type: Array, default: () => [] },  // [{ label, value, color }]
  size: { type: Number, default: 200 },
  centerLabel: { type: String, default: '总数' }
})

const V = 120
const c = V / 2
const r = 44
const thick = 16
const circ = 2 * Math.PI * r

const total = computed(() => props.segments.reduce((s, x) => s + (x.value || 0), 0))

const segs = computed(() => {
  const t = total.value
  if (!t) return []
  let acc = 0
  return props.segments
    .filter((s) => (s.value || 0) > 0)
    .map((s) => {
      const len = (s.value / t) * circ
      const o = { ...s, len, offset: acc }
      acc += len
      return o
    })
})
</script>

<style scoped>
.donut-wrap { display: flex; align-items: center; gap: 20px; }
.donut-chart { flex: none; }
.seg { transition: stroke-dasharray 0.3s ease; }
.total { font-size: 22px; font-weight: 700; fill: #1f2d3d; }
.total-label { font-size: 11px; fill: #94a3b3; }
.legend { list-style: none; margin: 0; padding: 0; min-width: 0; }
.legend li { display: flex; align-items: center; gap: 8px; padding: 4px 0; font-size: 13px; }
.sw { width: 10px; height: 10px; border-radius: 3px; flex: none; }
.lb { color: #5e6d82; }
.vl { margin-left: auto; color: #1f2d3d; font-weight: 600; padding-left: 16px; }
.empty { color: #94a3b3; }
</style>
