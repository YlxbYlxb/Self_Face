<script setup>
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const mode = ref('login')
const loading = ref(false)
const formRef = ref()
const form = reactive({ username: '', password: '', nickname: '' })

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '长度在 3 到 32 个字符', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' }
  ]
}

async function submit() {
  await formRef.value.validate()
  loading.value = true
  try {
    if (mode.value === 'login') {
      await userStore.login({ username: form.username, password: form.password })
      ElMessage.success('欢迎回来')
    } else {
      await userStore.register({
        username: form.username,
        password: form.password,
        nickname: form.nickname
      })
      ElMessage.success('注册成功，开始刷题吧')
    }
    router.push(route.query.redirect || '/dashboard')
  } catch (e) {
    // 错误提示已由请求拦截器统一处理
  } finally {
    loading.value = false
  }
}

function switchMode() {
  mode.value = mode.value === 'login' ? 'register' : 'login'
  formRef.value?.clearValidate()
}
</script>

<template>
  <div class="auth">
    <div class="auth-card">
      <div class="brand">
        <span class="dot" />
        <h1>SelfFace</h1>
      </div>
      <p class="sub">
        每天十道题，把八股变成肌肉记忆
      </p>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="3-32 位，字母数字下划线" size="large" />
        </el-form-item>

        <el-form-item v-if="mode === 'register'" label="昵称（可选）">
          <el-input v-model="form.nickname" placeholder="展示用，默认同用户名" size="large" />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="至少 6 位"
            size="large"
            show-password
            @keyup.enter="submit"
          />
        </el-form-item>

        <el-button type="primary" size="large" style="width: 100%" :loading="loading" @click="submit">
          {{ mode === 'login' ? '登录' : '注册并开始' }}
        </el-button>
      </el-form>

      <div class="switch">
        {{ mode === 'login' ? '还没有账号？' : '已经有账号了？' }}
        <a @click="switchMode">{{ mode === 'login' ? '立即注册' : '去登录' }}</a>
      </div>

      <div class="tips">
        <p>支持导入 112 道高频八股题，覆盖 Java、并发、JVM、MySQL、Redis、网络、操作系统、算法与项目场景。</p>
        <p>简历分析需要你自己的大模型 API Key，登录后在「设置」里配置。</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.auth {
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  background: linear-gradient(160deg, #eef2ff 0%, #f6f7fb 45%, #f0f7f4 100%);
}

.auth-card {
  width: 100%;
  max-width: 400px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 16px;
  padding: 34px 32px 26px;
  box-shadow: 0 12px 32px rgba(31, 41, 55, 0.06);
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.brand h1 {
  margin: 0;
  font-size: 21px;
  font-weight: 600;
  letter-spacing: 0.5px;
}

.dot {
  width: 12px;
  height: 12px;
  border-radius: 4px;
  background: var(--brand);
}

.sub {
  margin: 8px 0 24px;
  color: var(--ink-soft);
  font-size: 13px;
}

.switch {
  margin-top: 18px;
  text-align: center;
  font-size: 13px;
  color: var(--ink-soft);
}

.switch a {
  cursor: pointer;
  font-weight: 500;
}

.tips {
  margin-top: 22px;
  padding-top: 16px;
  border-top: 1px dashed var(--line);
  color: #9ca3af;
  font-size: 12px;
  line-height: 1.7;
}

.tips p {
  margin: 0 0 4px;
}
</style>
