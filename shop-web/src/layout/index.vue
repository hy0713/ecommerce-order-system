<template>
  <div class="app-shell">
    <!-- 左侧固定导航 -->
    <aside class="side">
      <div class="logo">
        <div class="logo-mark">轻</div>
        <div class="logo-text">
          <div class="logo-name">轻电商</div>
          <div class="logo-sub">管理后台</div>
        </div>
      </div>

      <nav class="menu">
        <router-link
          v-for="m in menus" :key="m.path"
          :to="m.path"
          class="menu-item"
          :class="{ active: isActive(m.path) }"
        >
          <el-icon :size="17"><component :is="m.icon" /></el-icon>
          <span>{{ m.title }}</span>
        </router-link>
      </nav>

      <div class="side-foot">
        <div class="foot-title">轻量电商订单系统</div>
        <div class="foot-sub">Java 后端 · 毕业设计</div>
      </div>
    </aside>

    <!-- 右侧：顶栏 + 内容 -->
    <div class="main">
      <header class="topbar">
        <div class="crumb">
          <span class="crumb-root">首页</span>
          <span class="crumb-sep">/</span>
          <span class="crumb-cur">{{ route.meta.title || '数据概览' }}</span>
        </div>

        <div class="top-right">
          <el-tag v-if="isAdmin" size="small" effect="dark" round class="role-tag admin">ADMIN</el-tag>
          <el-tag v-else size="small" effect="plain" round class="role-tag">USER</el-tag>
          <el-dropdown @command="handleCommand">
            <div class="user">
              <div class="avatar">{{ initial }}</div>
              <span class="uname">{{ userStore.userInfo?.username || '未登录' }}</span>
              <el-icon :size="14"><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>
                  {{ userStore.userInfo?.username }} · {{ isAdmin ? '管理员' : '普通用户' }}
                </el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowDown, ChatDotRound, Collection, Goods, Odometer, Tickets
} from '@element-plus/icons-vue'
import { useUserStore } from '../store/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const menus = [
  { path: '/dashboard', title: '数据概览', icon: Odometer },
  { path: '/products', title: '商品管理', icon: Goods },
  { path: '/categories', title: '分类管理', icon: Collection },
  { path: '/orders', title: '订单管理', icon: Tickets },
  { path: '/agent', title: '智能客服', icon: ChatDotRound }
]

const isActive = (p) => route.path === p || route.path.startsWith(p + '/')
const isAdmin = computed(() => userStore.userInfo?.role === 'ADMIN')
const initial = computed(() => (userStore.userInfo?.username || '?').slice(0, 1).toUpperCase())

const handleCommand = async (command) => {
  if (command === 'logout') {
    await userStore.logout()
    ElMessage.success('已退出登录')
    router.push('/login')
  }
}
</script>

<style scoped>
.app-shell { display: flex; height: 100vh; background: var(--bg); }

/* ---- 侧栏 ---- */
.side {
  width: 232px; flex: none; display: flex; flex-direction: column;
  background: #fff; border-right: 1px solid var(--border-2);
}
.logo { display: flex; align-items: center; gap: 12px; padding: 20px 20px 16px; }
.logo-mark {
  width: 40px; height: 40px; border-radius: 10px; flex: none;
  display: flex; align-items: center; justify-content: center;
  color: #fff; font-weight: 700; font-size: 18px;
  background: linear-gradient(135deg, #3b82f6, #2563eb);
  box-shadow: 0 6px 14px rgba(37, 99, 235, 0.3);
}
.logo-name { font-size: 16px; font-weight: 700; color: var(--text); }
.logo-sub { font-size: 12px; color: var(--text-3); margin-top: 2px; }

.menu { padding: 6px 12px; flex: 1; }
.menu-item {
  display: flex; align-items: center; gap: 12px;
  padding: 11px 14px; margin-bottom: 4px; border-radius: var(--radius-sm);
  color: var(--text-2); text-decoration: none; font-size: 14px;
  transition: background 0.18s, color 0.18s;
}
.menu-item:hover { background: var(--brand-50); color: var(--brand-600); }
.menu-item.active { background: var(--brand-50); color: var(--brand-600); font-weight: 600; }
.menu-item.active::before {
  content: ""; width: 3px; height: 18px; border-radius: 2px;
  background: var(--brand); margin-right: -6px; margin-left: -14px; padding-left: 3px;
}

.side-foot { padding: 16px 20px; border-top: 1px solid var(--border-2); }
.foot-title { font-size: 12px; color: var(--text-2); }
.foot-sub { font-size: 11px; color: var(--text-3); margin-top: 2px; }

/* ---- 顶栏 ---- */
.main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.topbar {
  height: 60px; flex: none; display: flex; align-items: center; justify-content: space-between;
  padding: 0 24px; background: #fff; border-bottom: 1px solid var(--border-2);
}
.crumb { font-size: 14px; color: var(--text-3); }
.crumb-sep { margin: 0 8px; }
.crumb-cur { color: var(--text); font-weight: 600; }

.top-right { display: flex; align-items: center; gap: 14px; }
.role-tag.admin { background: linear-gradient(135deg, #3b82f6, #2563eb); border: none; }
.user { display: flex; align-items: center; gap: 8px; cursor: pointer; color: var(--text); outline: none; }
.avatar {
  width: 32px; height: 32px; border-radius: 50%; flex: none;
  display: flex; align-items: center; justify-content: center;
  background: var(--brand-100); color: var(--brand-600); font-weight: 700; font-size: 14px;
}
.uname { font-size: 14px; }

/* ---- 内容 ---- */
.content { flex: 1; overflow-y: auto; }
</style>
