import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', name: 'Login', component: () => import('../views/Login.vue') },
  {
    path: '/',
    component: () => import('../layout/index.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'Dashboard', component: () => import('../views/Dashboard.vue'), meta: { title: '数据概览' } },
      { path: 'products', name: 'Products', component: () => import('../views/Product.vue'), meta: { title: '商品管理' } },
      { path: 'categories', name: 'Categories', component: () => import('../views/Category.vue'), meta: { title: '分类管理' } },
      { path: 'orders', name: 'Orders', component: () => import('../views/Order.vue'), meta: { title: '订单管理' } },
      { path: 'agent', name: 'Agent', component: () => import('../views/AgentChat.vue'), meta: { title: '智能客服' } }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫：无 token 跳转登录页
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token')
  if (to.path !== '/login' && !token) {
    next('/login')
  } else {
    next()
  }
})

export default router
