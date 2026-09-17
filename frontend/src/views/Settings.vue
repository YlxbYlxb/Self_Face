<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { llmApi, authApi } from '../api'
import { useUserStore } from '../stores/user'

const userStore = useUserStore()

const presets = [
  { name: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat' },
  { name: 'OpenAI', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini' },
  { name: '通义千问', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus' },
  { name: 'Kimi', baseUrl: 'https://api.moonshot.cn/v1', model: 'moonshot-v1-8k' },
  { name: '智谱 GLM', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-4-flash' },
  { name: '本地 Ollama', baseUrl: 'http://localhost:11434/v1', model: 'qwen2.5:7b' }
]

const llm = reactive({
  baseUrl: '',
  apiKey: '',
  model: '',
  temperature: 0.3,
  timeoutSeconds: 120
})
const maskedKey = ref('')
const configured = ref(false)
const savingLlm = ref(false)
const testing = ref(false)

const profile = reactive({
  nickname: '',
  email: '',
  targetCities: '',
  targetPosition: ''
})
const savingProfile = ref(false)

onMounted(async () => {
  const [setting, me] = await Promise.all([llmApi.get(), authApi.me()])
  llm.baseUrl = setting.baseUrl || ''
  llm.model = setting.model || ''
  llm.temperature = setting.temperature ?? 0.3
  llm.timeoutSeconds = setting.timeoutSeconds ?? 120
  maskedKey.value = setting.maskedKey
  configured.value = setting.configured

  profile.nickname = me.nickname || ''
  profile.email = me.email || ''
  profile.targetCities = me.targetCities || ''
  profile.targetPosition = me.targetPosition || ''
})

function applyPreset(p) {
  llm.baseUrl = p.baseUrl
  llm.model = p.model
  ElMessage.success(`已填入 ${p.name} 的地址与模型名，填上你的 API Key 即可`)
}

async function saveLlm() {
  savingLlm.value = true
  try {
    const res = await llmApi.save({
      baseUrl: llm.baseUrl,
      // 空字符串表示「不改动已保存的 Key」
      apiKey: llm.apiKey || null,
      model: llm.model,
      temperature: llm.temperature,
      timeoutSeconds: llm.timeoutSeconds
    })
    maskedKey.value = res.maskedKey
    configured.value = res.configured
    llm.apiKey = ''
    ElMessage.success(configured.value ? '配置已保存' : '已保存，但配置还不完整')
  } finally {
    savingLlm.value = false
  }
}

async function testConnection() {
  testing.value = true
  try {
    const res = await llmApi.test()
    ElMessage.success(res.message)
  } catch (e) {
    // 拦截器已提示
  } finally {
    testing.value = false
  }
}

async function saveProfile() {
  savingProfile.value = true
  try {
    const user = await authApi.updateProfile({
      nickname: profile.nickname,
      email: profile.email,
      targetCities: profile.targetCities,
      targetPosition: profile.targetPosition
    })
    userStore.user = user
    localStorage.setItem('user', JSON.stringify(user))
    ElMessage.success('资料已更新')
  } finally {
    savingProfile.value = false
  }
}
</script>

<template>
  <div class="page narrow">
    <div class="page-head">
      <h2>设置</h2>
      <p>大模型使用你自己的 Key，服务端只做转发，不会替你调用任何第三方模型。</p>
    </div>

    <div class="card">
      <div class="head">
        <h3>大模型配置</h3>
        <el-tag v-if="configured" type="success" effect="plain" size="small">已配置</el-tag>
        <el-tag v-else type="warning" effect="plain" size="small">未配置</el-tag>
      </div>

      <div class="presets">
        <span class="muted small">快速填充：</span>
        <el-button v-for="p in presets" :key="p.name" size="small" plain @click="applyPreset(p)">
          {{ p.name }}
        </el-button>
      </div>

      <el-form label-position="top">
        <el-form-item label="Base URL">
          <el-input v-model="llm.baseUrl" placeholder="https://api.deepseek.com/v1" />
          <div class="tip">兼容 OpenAI 协议即可，通常需要以 /v1 结尾。</div>
        </el-form-item>

        <el-form-item label="API Key">
          <el-input
            v-model="llm.apiKey"
            type="password"
            show-password
            :placeholder="maskedKey ? `已保存：${maskedKey}（留空表示不修改）` : 'sk-...'"
          />
          <div class="tip">只会保存在你自己的数据库里。接口不会回传明文，页面刷新后只显示掩码。</div>
        </el-form-item>

        <el-form-item label="模型名">
          <el-input v-model="llm.model" placeholder="deepseek-chat" />
        </el-form-item>

        <div class="row">
          <el-form-item label="温度（越低越稳定）">
            <el-slider v-model="llm.temperature" :min="0" :max="1.5" :step="0.1" show-input />
          </el-form-item>
          <el-form-item label="超时（秒）">
            <el-input-number v-model="llm.timeoutSeconds" :min="20" :max="600" :step="10" />
          </el-form-item>
        </div>
      </el-form>

      <div class="actions">
        <el-button type="primary" :loading="savingLlm" @click="saveLlm">保存配置</el-button>
        <el-button :loading="testing" :disabled="!configured" @click="testConnection">
          测试连接
        </el-button>
      </div>
    </div>

    <div class="card">
      <h3>个人资料</h3>
      <p class="muted small mb">目标岗位和城市会作为简历分析的输入，模型会据此调整出题角度。</p>
      <el-form label-position="top">
        <div class="row">
          <el-form-item label="昵称">
            <el-input v-model="profile.nickname" placeholder="展示用" />
          </el-form-item>
          <el-form-item label="邮箱">
            <el-input v-model="profile.email" placeholder="可留空" />
          </el-form-item>
        </div>
        <el-form-item label="求职目标城市">
          <el-input v-model="profile.targetCities" placeholder="武汉,长沙,广州,成都,深圳" />
        </el-form-item>
        <el-form-item label="目标岗位">
          <el-input v-model="profile.targetPosition" placeholder="Java 后端开发实习生" />
        </el-form-item>
      </el-form>
      <div class="actions">
        <el-button type="primary" :loading="savingProfile" @click="saveProfile">保存资料</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.narrow {
  max-width: 720px;
}

.card {
  margin-bottom: 16px;
}

.card h3 {
  margin: 0 0 14px;
  font-size: 15px;
  font-weight: 600;
}

.head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}

.head h3 {
  margin: 0;
}

.presets {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 12px 14px;
  background: #fafbfd;
  border: 1px solid var(--line);
  border-radius: 10px;
  margin-bottom: 18px;
}

.small {
  font-size: 12px;
}

.tip {
  font-size: 12px;
  color: #9ca3af;
  line-height: 1.6;
  margin-top: 4px;
}

.row {
  display: grid;
  grid-template-columns: 1fr 200px;
  gap: 16px;
}

.mb {
  margin: 0 0 14px;
}

.actions {
  display: flex;
  gap: 10px;
  padding-top: 6px;
}

:deep(.el-form-item) {
  margin-bottom: 16px;
}
</style>
