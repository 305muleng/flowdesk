<script setup lang="ts">
import { Lock, User } from '@element-plus/icons-vue'
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const form = ref({ username: '', password: '' })
const formRef = ref()
const loading = ref(false)
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}
async function submit() {
  await formRef.value?.validate()
  loading.value = true
  try {
    await auth.login(form.value)
    await router.replace(
      typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard',
    )
  } finally {
    loading.value = false
  }
}
</script>
<template>
  <main class="login-page">
    <section class="brand-panel">
      <div class="brand-mark">F</div>
      <p class="eyebrow">FLOWDESK</p>
      <h1>让每一次协作<br />都清晰推进。</h1>
      <p class="brand-copy">项目、任务与交付进度，集中在一个轻盈而可靠的工作空间里。</p>
      <div class="feature-list">
        <span>项目全景</span><span>任务协作</span><span>交付闭环</span>
      </div>
    </section>
    <section class="login-panel">
      <div class="login-card">
        <p class="eyebrow">WELCOME BACK</p>
        <h2>登录 FlowDesk</h2>
        <p class="subcopy">使用你的账号继续管理工作。</p>
        <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="submit"
          ><el-form-item prop="username"
            ><el-input
              v-model="form.username"
              :prefix-icon="User"
              placeholder="用户名" /></el-form-item
          ><el-form-item prop="password"
            ><el-input
              v-model="form.password"
              :prefix-icon="Lock"
              type="password"
              show-password
              placeholder="密码" /></el-form-item
          ><el-button class="login-button" type="primary" :loading="loading" @click="submit"
            >登 录</el-button
          ></el-form
        >
        <p class="login-hint">还没有账号？<router-link to="/register">立即注册</router-link></p>
      </div>
    </section>
  </main>
</template>
<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(360px, 46%) 1fr;
  background: #f8fafc;
}
.brand-panel {
  padding: clamp(42px, 7vw, 108px);
  color: #fff;
  background:
    radial-gradient(circle at 18% 18%, #447dff 0, transparent 28%),
    linear-gradient(145deg, #111f4a 0%, #192f71 55%, #294bb1 100%);
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.brand-mark {
  width: 48px;
  height: 48px;
  border-radius: 14px;
  display: grid;
  place-items: center;
  background: #fff;
  color: #2449af;
  font-size: 26px;
  font-weight: 800;
}
.eyebrow {
  margin: 28px 0 12px;
  letter-spacing: 0.16em;
  font-size: 12px;
  font-weight: 700;
  color: #a9c2ff;
}
h1 {
  margin: 0;
  font-size: clamp(35px, 4vw, 58px);
  line-height: 1.13;
  letter-spacing: -0.045em;
}
.brand-copy {
  max-width: 430px;
  margin: 26px 0 0;
  color: #d5e2ff;
  line-height: 1.8;
  font-size: 16px;
}
.feature-list {
  display: flex;
  gap: 10px;
  margin-top: 38px;
  flex-wrap: wrap;
}
.feature-list span {
  padding: 8px 13px;
  border: 1px solid rgba(255, 255, 255, 0.28);
  border-radius: 99px;
  color: #e5edff;
  font-size: 13px;
}
.login-panel {
  display: grid;
  place-items: center;
  padding: 32px;
}
.login-card {
  width: min(100%, 390px);
}
.login-card .eyebrow {
  color: #5370c9;
  margin: 0 0 14px;
}
h2 {
  margin: 0;
  color: #17223d;
  font-size: 30px;
  letter-spacing: -0.04em;
}
.subcopy {
  margin: 12px 0 32px;
  color: #77819a;
}
.login-button {
  width: 100%;
  height: 48px;
  margin-top: 8px;
  font-weight: 700;
}
.login-hint {
  margin-top: 22px;
  font-size: 12px;
  color: #98a1b5;
  text-align: center;
}
.login-hint a {
  color: #315fc5;
  text-decoration: none;
  font-weight: 600;
}
@media (max-width: 760px) {
  .login-page {
    grid-template-columns: 1fr;
  }
  .brand-panel {
    display: none;
  }
}
</style>
