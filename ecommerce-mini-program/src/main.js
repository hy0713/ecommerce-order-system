import { createSSRApp } from 'vue'
import { createPinia } from 'pinia'
import uviewPlus, { setConfig } from 'uview-plus'
import App from './App.vue'

// 图标字体本地化：uView Plus 微信端默认从阿里 CDN 加载字体，
// 覆盖为包内 static 资源，保证离线可用（与 urlCheck 域名白名单解耦）
setConfig({ config: { iconUrl: '/static/fonts/upicon.ttf' } })

export function createApp() {
  const app = createSSRApp(App)
  app.use(createPinia())
  app.use(uviewPlus)
  return { app }
}
