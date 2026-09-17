<template>
  <div class="login-page">
    <div class="login-card card">
      <div class="brand">
        <div class="brand-mark">轻</div>
        <div class="brand-name">轻电商</div>
        <div class="brand-sub">电商管理系统</div>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" size="large" @submit.prevent>
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" :prefix-icon="User" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password" type="password" placeholder="密码"
            show-password :prefix-icon="Lock" @keyup.enter="handleLogin"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="login-btn" :loading="loading" @click="handleLogin">
            登 录
          </el-button>
        </el-form-item>
      </el-form>

      <div class="demo-tip">
        <span class="demo-label">开发演示账号</span>
        <span class="demo-value">admin / 123456</span>
      </div>
    </div>

    <div class="login-foot">轻量电商订单系统 · Java 后端毕业设计</div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import { useUserStore } from '../store/user'

const router = useRouter()
const userStore = useUserStore()
const formRef = ref()
const loading = ref(false)
const form = reactive({ username: 'admin', password: '123456' })
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const handleLogin = async () => {
  await formRef.value.validate()
  loading.value = true
  try {
    await userStore.login(form)
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100vh; display: flex; flex-direction: column;
  align-items: center; justify-content: center;
  background:
    radial-gradient(1000px 500px at 80% -10%, rgba(59, 130, 246, 0.10), transparent),
    radial-gradient(800px 400px at 10% 110%, rgba(37, 99, 235, 0.08), transparent),
    var(--bg);
  padding: 24px;
}
.login-card { width: 400px; padding: 36px 36px 28px; }
.brand { text-align: center; margin-bottom: 26px; }
.brand-mark {
  width: 52px; height: 52px; border-radius: 14px; margin: 0 auto 14px;
  display: flex; align-items: center; justify-content: center;
  color: #fff; font-size: 24px; font-weight: 700;
  background: linear-gradient(135deg, #3b82f6, #2563eb);
  box-shadow: 0 10px 22px rgba(37, 99, 235, 0.32);
}
.brand-name { font-size: 20px; font-weight: 700; color: var(--text); }
.brand-sub { font-size: 13px; color: var(--text-3); margin-top: 4px; }
.login-btn { width: 100%; height: 44px; font-size: 15px; letter-spacing: 2px; }
.demo-tip {
  margin-top: 6px; padding: 10px 14px; border-radius: var(--radius-sm);
  background: var(--brand-50); display: flex; align-items: center; justify-content: center; gap: 10px;
}
.demo-label { font-size: 12px; color: var(--text-3); }
.demo-value { font-size: 13px; color: var(--brand-600); font-weight: 600; font-variant-numeric: tabular-nums; }
.login-foot { margin-top: 22px; font-size: 12px; color: var(--text-3); }
</style>
