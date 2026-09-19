<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { registerApi } from '@/api/auth'
const router = useRouter()
const form = ref({ username: '', password: '', realName: '' })
const loading = ref(false)
async function submit() {
  if (form.value.username.length < 3 || form.value.password.length < 6 || !form.value.realName) {
    ElMessage.warning('请完整填写信息，用户名至少3位、密码至少6位')
    return
  }
  loading.value = true
  try {
    await registerApi(form.value)
    ElMessage.success('注册成功，请登录')
    router.replace('/login')
  } finally {
    loading.value = false
  }
}
</script>
<template>
  <main class="page">
    <el-card class="card"
      ><h1>创建 FlowDesk 账号</h1>
      <el-form label-position="top"
        ><el-form-item label="真实姓名"><el-input v-model="form.realName" /></el-form-item
        ><el-form-item label="用户名"><el-input v-model="form.username" /></el-form-item
        ><el-form-item label="密码"
          ><el-input v-model="form.password" type="password" show-password /></el-form-item
        ><el-button type="primary" class="full" :loading="loading" @click="submit">注册</el-button
        ><el-button class="full link" text @click="router.push('/login')"
          >返回登录</el-button
        ></el-form
      ></el-card
    >
  </main>
</template>
<style scoped>
.page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  background: #f6f8fc;
}
.card {
  width: min(92vw, 430px);
  padding: 20px;
}
.card h1 {
  margin: 0 0 26px;
  color: #23304c;
}
.full {
  width: 100%;
}
.link {
  margin: 10px 0 0;
}
</style>
