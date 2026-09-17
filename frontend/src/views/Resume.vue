<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { resumeApi } from '../api'

const uploadRef = ref()
const fileList = ref([])
const pickedFile = ref(null)

const analyzing = ref(false)
const result = ref(null)
const history = ref([])
const historyLoading = ref(false)
const elapsed = ref(0)
let timer = null

const profile = computed(() => safeParse(result.value?.profileJson))
const questionData = computed(() => safeParse(result.value?.questionsJson))

const focusTopics = computed(() => questionData.value?.interviewFocus || [])
const groups = computed(() => questionData.value?.groups || [])
const plan = computed(() => questionData.value?.preparationPlan || [])
const skills = computed(() => profile.value?.skills || [])
const projects = computed(() => profile.value?.projects || [])
const risks = computed(() => profile.value?.risks || [])
// 命中题库的题目数由预测问题里的 bankQuestionId 统计，服务端不再单独回传这个数
const bankHits = computed(() => {
  let n = 0
  for (const g of groups.value) {
    for (const q of g.questions || []) {
      if (q.bankQuestionId) n += 1
    }
  }
  return n
})

onMounted(loadHistory)

function safeParse(text) {
  if (!text) return null
  try {
    return JSON.parse(text)
  } catch (e) {
    return null
  }
}

async function loadHistory() {
  historyLoading.value = true
  try {
    history.value = await resumeApi.list(20)
  } finally {
    historyLoading.value = false
  }
}

function onChange(file) {
  pickedFile.value = file.raw
  fileList.value = [file]
}

function onRemove() {
  pickedFile.value = null
  fileList.value = []
}

async function analyze() {
  if (!pickedFile.value) {
    ElMessage.warning('请先选择一份简历文件')
    return
  }
  analyzing.value = true
  result.value = null
  elapsed.value = 0
  timer = setInterval(() => { elapsed.value += 1 }, 1000)
  try {
    // 上传只负责建任务，服务端后台线程去调大模型，所以这里立刻返回
    const job = await resumeApi.analyze(pickedFile.value)
    result.value = await pollResult(job.id)
    if (result.value?.status === 'SUCCESS') {
      ElMessage.success(`分析完成，命中题库 ${bankHits.value} 道相关题目`)
    } else {
      ElMessage.warning(result.value?.errorMsg || '分析未成功完成')
    }
    await loadHistory()
  } catch (e) {
    // 拦截器已提示
  } finally {
    analyzing.value = false
    clearInterval(timer)
    timer = null
  }
}

const POLL_TIMEOUT_MS = 10 * 60 * 1000
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/** 轮询任务状态：间隔从 1.5 秒逐步放宽到 5 秒，避免把后端刷爆 */
async function pollResult(id) {
  const deadline = Date.now() + POLL_TIMEOUT_MS
  let delay = 1500
  let last = null
  for (;;) {
    try {
      last = await resumeApi.detail(id)
    } catch (e) {
      return { id, status: 'FAILED', errorMsg: '查询分析结果失败，请稍后到下方历史记录里查看' }
    }
    if (last && last.status !== 'PENDING' && last.status !== 'RUNNING') {
      return last
    }
    if (Date.now() >= deadline) {
      return { ...(last || { id }), status: 'FAILED', errorMsg: '分析耗时过长，请稍后到下方历史记录里查看结果' }
    }
    await sleep(delay)
    delay = Math.min(Math.round(delay * 1.4), 5000)
  }
}

async function openHistory(item) {
  if (item.status !== 'SUCCESS') {
    ElMessage.warning(item.errorMsg || '这次分析没有成功完成')
    return
  }
  result.value = await resumeApi.detail(item.id)
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

async function removeHistory(item) {
  await resumeApi.remove(item.id)
  ElMessage.success('已删除')
  if (result.value?.id === item.id) result.value = null
  await loadHistory()
}

function diffType(d) {
  return d === 1 ? 'info' : d === 3 ? 'danger' : 'warning'
}

function diffText(d) {
  return d === 1 ? '简单' : d === 3 ? '困难' : '中等'
}

function typeColor(t) {
  const map = { 基础: 'info', 深挖: 'warning', 场景: 'danger', 项目: 'success' }
  return map[t] || 'info'
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h2>简历分析</h2>
      <p>
        上传简历，模型先从你的技能与项目里抽结构化画像，再回题库检索相关考点，
        最后生成一份「大概率被问到」的问题清单与追问链。全程使用你自己的 API Key。
      </p>
    </div>

    <div class="card uploader">
      <el-upload
        ref="uploadRef"
        drag
        :auto-upload="false"
        :limit="1"
        :file-list="fileList"
        accept=".pdf,.docx,.txt,.md"
        :on-change="onChange"
        :on-remove="onRemove"
      >
        <el-icon class="up-icon"><UploadFilled /></el-icon>
        <div class="up-text">把简历拖到这里，或<em>点击选择文件</em></div>
        <template #tip>
          <div class="up-tip">支持 PDF / DOCX / TXT，10MB 以内。扫描件请先转成文字版 PDF。</div>
        </template>
      </el-upload>

      <div class="upload-actions">
        <el-button type="primary" size="large" :loading="analyzing" @click="analyze">
          {{ analyzing ? `正在分析…已用 ${elapsed} 秒` : '开始分析' }}
        </el-button>
      </div>
    </div>

    <div v-if="analyzing" class="card" style="display: flex; gap: 12px; align-items: center">
      <el-icon class="is-loading" style="font-size: 20px"><Loading /></el-icon>
      <div class="muted">
        任务已提交，服务端正在读取全文并做两轮推理（通常 20-90 秒）。
        <b>现在可以离开这个页面</b>，结果会保存在下方历史记录里。
      </div>
    </div>

    <div v-if="result && result.status === 'SUCCESS'" class="result">
      <div class="card summary-card">
        <div class="summary-top">
          <h3>总评</h3>
          <span class="muted small">
            {{ result.fileName }} · 模型 {{ result.model }} ·
            消耗 {{ (result.promptTokens || 0) + (result.completionTokens || 0) }} tokens
          </span>
        </div>
        <p class="summary-text">{{ result.summary }}</p>
        <div v-if="focusTopics.length" class="focus">
          <span class="muted small">最可能围绕这些主题：</span>
          <el-tag v-for="t in focusTopics" :key="t" type="primary" effect="light" style="margin: 4px 6px 0 0">
            {{ t }}
          </el-tag>
        </div>
      </div>

      <div v-if="skills.length || projects.length" class="card">
        <h3>简历画像</h3>
        <div v-if="skills.length" class="block">
          <div class="block-label">技能</div>
          <div class="skill-list">
            <el-tooltip v-for="s in skills" :key="s.name" :content="s.evidence || '简历未提供支撑说明'" placement="top">
              <el-tag effect="plain" style="margin: 0 6px 6px 0">
                {{ s.name }}<span v-if="s.level" class="lv">· {{ s.level }}</span>
              </el-tag>
            </el-tooltip>
          </div>
        </div>
        <div v-if="projects.length" class="block">
          <div class="block-label">项目</div>
          <div v-for="p in projects" :key="p.name" class="proj">
            <div class="proj-name">{{ p.name }}<span v-if="p.role" class="muted"> · {{ p.role }}</span></div>
            <div v-if="p.techStack?.length" class="proj-tech">
              <el-tag v-for="t in p.techStack" :key="t" size="small" effect="plain" style="margin: 0 6px 6px 0">
                {{ t }}
              </el-tag>
            </div>
            <ul v-if="p.highlights?.length" class="proj-list">
              <li v-for="h in p.highlights" :key="h">{{ h }}</li>
            </ul>
            <div v-if="p.weakPoints?.length" class="weak">
              <span class="weak-label">可能被质疑：</span>
              <span v-for="w in p.weakPoints" :key="w" class="weak-item">{{ w }}</span>
            </div>
          </div>
        </div>
        <div v-if="risks.length" class="block">
          <div class="block-label">面试风险点</div>
          <ul class="proj-list risk-list">
            <li v-for="r in risks" :key="r">{{ r }}</li>
          </ul>
        </div>
      </div>

      <div class="card">
        <div class="summary-top">
          <h3>预测面试问题</h3>
          <span class="muted small">{{ groups.length }} 个考察方向</span>
        </div>
        <el-collapse>
          <el-collapse-item v-for="(g, gi) in groups" :key="gi" :name="gi">
            <template #title>
              <span class="group-title">{{ g.name }}</span>
              <span class="muted group-count">{{ g.questions?.length || 0 }} 题</span>
            </template>
            <p class="group-reason">{{ g.reason }}</p>
            <div v-for="(q, qi) in g.questions" :key="qi" class="q">
              <div class="q-head">
                <span class="q-index">{{ qi + 1 }}</span>
                <span class="q-title">{{ q.question }}</span>
              </div>
              <div class="q-meta">
                <el-tag size="small" :type="typeColor(q.type)" effect="plain">{{ q.type }}</el-tag>
                <el-tag size="small" :type="diffType(q.difficulty)" effect="plain">
                  {{ diffText(q.difficulty) }}
                </el-tag>
                <el-tag v-if="q.bankQuestionId" size="small" type="success" effect="plain">题库已有</el-tag>
              </div>
              <div v-if="q.why" class="q-why"><b>考察点</b>{{ q.why }}</div>
              <div v-if="q.followUps?.length" class="q-line">
                <b>追问链</b>
                <ol class="q-ol">
                  <li v-for="f in q.followUps" :key="f">{{ f }}</li>
                </ol>
              </div>
              <div v-if="q.keyPoints?.length" class="q-line">
                <b>答题要点</b>
                <ul class="q-ul">
                  <li v-for="k in q.keyPoints" :key="k">{{ k }}</li>
                </ul>
              </div>
            </div>
          </el-collapse-item>
        </el-collapse>
      </div>

      <div v-if="plan.length" class="card">
        <h3>复习优先级建议</h3>
        <ol class="plan">
          <li v-for="p in plan" :key="p">{{ p }}</li>
        </ol>
      </div>
    </div>

    <div v-else-if="result && result.status === 'FAILED'" class="card failed">
      <h3>这次分析没有成功</h3>
      <p class="muted">{{ result.errorMsg }}</p>
      <p class="muted small">常见原因：API Key 或 Base URL 配置有误、模型名写错、账户余额不足。可以到「设置」页点一次「测试连接」确认。</p>
    </div>

    <div class="card history">
      <div class="summary-top">
        <h3>历史分析</h3>
        <el-button text :loading="historyLoading" @click="loadHistory">
          <el-icon style="margin-right: 4px"><Refresh /></el-icon>刷新
        </el-button>
      </div>
      <el-empty v-if="!history.length" description="还没有分析记录" :image-size="80" />
      <div v-for="h in history" :key="h.id" class="h-item">
        <div class="h-main" @click="openHistory(h)">
          <div class="h-name">{{ h.fileName }}</div>
          <div class="h-sub muted">
            {{ h.createdAt?.replace('T', ' ').slice(0, 16) }} · {{ h.model }}
            <span v-if="h.tokens"> · {{ h.tokens }} tokens</span>
          </div>
          <div v-if="h.summary" class="h-summary">{{ h.summary }}</div>
        </div>
        <div class="h-right">
          <el-tag size="small" :type="h.status === 'SUCCESS' ? 'success' : 'danger'" effect="plain">
            {{ h.status === 'SUCCESS' ? '成功' : '失败' }}
          </el-tag>
          <el-button text type="danger" size="small" @click.stop="removeHistory(h)">删除</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.uploader {
  margin-bottom: 18px;
}

.up-icon {
  font-size: 46px;
  color: #c3c8d2;
  margin-bottom: 8px;
}

.up-text {
  color: var(--ink-soft);
  font-size: 14px;
}

.up-text em {
  color: var(--brand);
  font-style: normal;
}

.up-tip {
  color: #9ca3af;
  font-size: 12px;
  margin-top: 8px;
}

.upload-actions {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 16px;
}

.hint {
  font-size: 12px;
}

.result {
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin-bottom: 18px;
}

.result h3 {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
}

.summary-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.summary-top h3 {
  margin-bottom: 0;
}

.small {
  font-size: 12px;
}

.summary-text {
  margin: 12px 0 0;
  font-size: 15px;
  line-height: 1.7;
}

.focus {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px dashed var(--line);
}

.block {
  margin-bottom: 16px;
}

.block:last-child {
  margin-bottom: 0;
}

.block-label {
  font-size: 12px;
  color: var(--ink-soft);
  margin-bottom: 8px;
}

.lv {
  color: #9ca3af;
  font-size: 11px;
}

.skill-list {
  display: flex;
  flex-wrap: wrap;
}

.proj {
  padding: 12px 14px;
  background: #fafbfd;
  border: 1px solid var(--line);
  border-radius: 10px;
  margin-bottom: 10px;
}

.proj-name {
  font-size: 14px;
  font-weight: 500;
  margin-bottom: 8px;
}

.proj-tech {
  display: flex;
  flex-wrap: wrap;
  margin-bottom: 6px;
}

.proj-list,
.q-ul,
.q-ol {
  margin: 6px 0;
  padding-left: 20px;
  font-size: 13px;
  line-height: 1.7;
  color: #374151;
}

.weak {
  margin-top: 8px;
  padding: 8px 10px;
  background: #fff7f5;
  border-radius: 8px;
  font-size: 13px;
}

.weak-label {
  color: #c05621;
  font-weight: 500;
}

.weak-item {
  color: #7b341e;
  margin-right: 10px;
}

.risk-list li {
  color: #a0342a;
}

.group-title {
  font-size: 14px;
  font-weight: 500;
}

.group-count {
  font-size: 12px;
  margin-left: 10px;
}

.group-reason {
  margin: 4px 0 14px;
  font-size: 13px;
  color: var(--ink-soft);
  line-height: 1.7;
}

.q {
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: 10px;
  margin-bottom: 10px;
  background: #fff;
}

.q-head {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

.q-index {
  flex: none;
  width: 20px;
  height: 20px;
  border-radius: 6px;
  background: var(--brand-soft);
  color: var(--brand);
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 2px;
}

.q-title {
  font-size: 14px;
  font-weight: 500;
  line-height: 1.6;
}

.q-meta {
  display: flex;
  gap: 6px;
  margin-bottom: 10px;
}

.q-why,
.q-line {
  font-size: 13px;
  color: #374151;
  line-height: 1.7;
  margin-bottom: 8px;
}

.q-why b,
.q-line b {
  display: inline-block;
  min-width: 58px;
  color: var(--brand);
  font-weight: 500;
  margin-right: 6px;
}

.plan {
  margin: 0;
  padding-left: 22px;
  font-size: 14px;
  line-height: 1.9;
}

.failed {
  border-color: #f3c9c9;
  background: #fffafa;
  margin-bottom: 18px;
}

.failed h3 {
  margin: 0 0 10px;
}

.failed p {
  margin: 6px 0;
  font-size: 13px;
  line-height: 1.7;
}

.history {
  margin-bottom: 20px;
}

.h-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-top: 1px solid var(--line);
}

.h-item:first-of-type {
  border-top: none;
}

.h-main {
  flex: 1;
  min-width: 0;
  cursor: pointer;
}

.h-name {
  font-size: 14px;
  font-weight: 500;
  margin-bottom: 3px;
}

.h-sub {
  font-size: 12px;
}

.h-summary {
  font-size: 13px;
  color: #4b5563;
  margin-top: 5px;
  line-height: 1.6;
}

.h-right {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
}
</style>
