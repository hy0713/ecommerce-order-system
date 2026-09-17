import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

// uni-app CLI 默认源码目录为 src/（官方推荐结构），无需额外配置
export default defineConfig({
  plugins: [uni()],
  build: {
    // uview-plus 图标字体(upicon.ttf 55KB)以内联 base64 打包，
    // 避免 mp-weixin 产物缺少字体文件导致图标空白
    assetsInlineLimit: 100 * 1024
  }
})
