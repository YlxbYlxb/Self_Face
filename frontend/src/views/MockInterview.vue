<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { interviewApi, resumeApi } from '../api'

const form = ref({
  resumeId: null,
  role: '',
  level: 'junior',
  type: 'technical',
  maxRounds: 5
})

const session = ref(null)
const resumes = ref([])
const history = ref([])
const loading = ref(false)
const thinking = ref(false)
const answer = ref('')
const transcriptRef = ref(null)

const turns = computed(() => session.value?.turns || [])
const isRunning = computed(() => session.value?.status === 'RUNNING')
const report = computed(() => session.value?.report || null)
const answeredCount = computed(() => session.value?.round || 0)
const reachedTarget = computed(() => answeredCount.value >= (session.value?.maxRounds || 5))

onMounted(load)

async function load() {
  loading.value = true
  try {
    const [list, resumeList] = await Promise.all([
      interviewApi.list(10),
      resumeApi.list(20).catch(() => [])
    ])
    history.value = list || []
    // 只有解析成功的简历才能当题源
    resumes.value = (resumeList || []).filter((r) => r.status === 'SUCCESS')
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

async function start() {
  thinking.value = true
  try {
    session.value = await interviewApi.start({
      resumeId: form.value.resumeId || null,
      role: form.value.role || null,
      level: form.value.level,
      type: form.value.type,
      maxRounds: form.value.maxRounds
    })
    answer.value = ''
    await scrollToBottom()
    await load()
  } catch (e) {
    // 拦截器已提示
  } finally {
    thinking.value = false
  }
}

async function submitAnswer() {
  const text = answer.value.trim()
  if (text.length < 2) {
    ElMessage.warning('先把你的回答写下来再提交')
    return
  }
  thinking.value = true
  try {
    session.value = await interviewApi.answer(session.value.id, text)
    answer.value = ''
    await scrollToBottom()
  } catch (e) {
    // 拦截器已提示
  } finally {
    thinking.value = false
  }
}

async function finish() {
  thinking.value = true
  try {
    session.value = await interviewApi.finish(session.value.id)
    await load()
    await scrollToBottom()
  } catch (e) {
    // 拦截器已提示
  } finally {
    thinking.value = false
  }
}

async function openSession(id) {
  loading.value = true
  try {
    session.value = await interviewApi.detail(id)
    await scrollToBottom()
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function backToStart() {
  session.value = null
  answer.value = ''
}

async function scrollToBottom() {
  await nextTick()
  if (transcriptRef.value) {
    transcriptRef.value.scrollTop = transcriptRef.value.scrollHeight
  }
}

function scoreType(score) {
  if (score == null) return 'info'
  if (score >= 80) return 'success'
  if (score >= 60) return 'warning'
  return 'danger'
}

function levelText(l) {
  return { junior: '初级 · 实习/校招', mid: '中级 · 1-3 年', senior: '高级 · 3 年以上' }[l] || l
}

function typeText(t) {
  return { technical: '技术面', project: '项目面', comprehensive: '综合面' }[t] || t
}

function briefTime(s) {
  return s ? String(s).replace('T', ' ').slice(0, 16) : ''
}
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <h2>模拟面试</h2>
      <p>
        面试官只会提问和追问，不会给你答案 —— 因为真实面试里也没有参考答案。
        答完会立刻给出点评，最后生成一份评估报告。
      </p>
    </div>

    <!-- 未开始：配置 + 历史 -->
    <template v-if="!session">
      <div class="card setup">
        <el-form label-width="88px" label-position="left">
          <el-form-item label="题源简历">
            <el-select v-model="form.resumeId" placeholder="不选则只按岗位出题" clearable style="width: 100%">
              <el-option
                v-for="r in resumes"
                :key="r.id"
                :label="`${r.fileName || '未命名简历'}（${briefTime(r.createdAt)}）`"
                :value="r.id"
              />
            </el-select>
            <div class="hint">
              选一份已解析的简历，面试官会围绕你的项目提问；没有的话先去「简历分析」上传一份。
            </div>
          </el-form-item>
          <el-form-item label="目标岗位">
            <el-input v-model="form.role" placeholder="例如：Java 后端开发实习生" clearable />
          </el-form-item>
          <el-form-item label="面试级别">
            <el-radio-group v-model="form.level">
              <el-radio-button value="junior">初级</el-radio-button>
              <el-radio-button value="mid">中级</el-radio-button>
              <el-radio-button value="senior">高级</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="面试类型">
            <el-radio-group v-model="form.type">
              <el-radio-button value="technical">技术面</el-radio-button>
              <el-radio-button value="project">项目面</el-radio-button>
              <el-radio-button value="comprehensive">综合面</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="问答轮数">
            <el-radio-group v-model="form.maxRounds">
              <el-radio-button :value="3">3 轮</el-radio-button>
              <el-radio-button :value="5">5 轮</el-radio-button>
              <el-radio-button :value="8">8 轮</el-radio-button>
            </el-radio-group>
          </el-form-item>
        </el-form>
        <div class="setup-actions">
          <span class="muted">每轮都会调用你配置的大模型</span>
          <el-button type="primary" :loading="thinking" @click="start">
            {{ thinking ? '面试官准备中…' : '开始面试' }}
          </el-button>
        </div>
      </div>

      <div v-if="history.length" class="card">
        <h3>面试记录</h3>
        <div class="history">
          <div v-for="h in history" :key="h.id" class="history-item" @click="openSession(h.id)">
            <div class="hi-main">
              <span class="hi-role">{{ h.role || '未指定岗位' }}</span>
              <span class="hi-meta">{{ levelText(h.level) }} · {{ typeText(h.type) }}</span>
            </div>
            <div class="hi-right">
              <el-tag v-if="h.status === 'FINISHED'" :type="scoreType(h.overallScore)" effect="plain" size="small">
                {{ h.overallScore ?? '—' }} 分
              </el-tag>
              <el-tag v-else type="info" effect="plain" size="small">进行中</el-tag>
              <span class="hi-time">{{ briefTime(h.startedAt) }}</span>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- 进行中 / 已结束：对话 -->
    <template v-else>
      <div class="card session-head">
        <div>
          <span class="sh-role">{{ session.role || '未指定岗位' }}</span>
          <span class="sh-meta">{{ levelText(session.level) }} · {{ typeText(session.type) }}</span>
        </div>
        <div class="sh-right">
          <span class="muted">已答 {{ answeredCount }} / {{ session.maxRounds }} 轮</span>
          <el-button link @click="backToStart">返回列表</el-button>
          <el-button
            v-if="isRunning"
            :type="reachedTarget ? 'primary' : 'default'"
            :loading="thinking"
            @click="finish"
          >
            结束并出报告
          </el-button>
        </div>
      </div>

      <div ref="transcriptRef" class="card transcript">
        <div v-for="t in turns" :key="t.id" class="turn" :class="t.role">
          <div class="who">{{ t.role === 'interviewer' ? '面试官' : '我' }}</div>
          <div class="bubble">
            <div class="bubble-text">{{ t.content }}</div>
            <div v-if="t.role === 'interviewer' && t.comment" class="eval">
              <el-tag :type="scoreType(t.score)" size="small" effect="dark">
                上一轮 {{ t.score ?? '—' }} 分
              </el-tag>
              <span class="eval-text">{{ t.comment }}</span>
            </div>
          </div>
        </div>

        <div v-if="thinking" class="turn interviewer">
          <div class="who">面试官</div>
          <div class="bubble thinking">正在思考…</div>
        </div>
      </div>

      <div v-if="isRunning" class="card answer-box">
        <el-input
          v-model="answer"
          type="textarea"
          :rows="5"
          resize="vertical"
          placeholder="像在面试现场一样，用嘴说出来的话把它写下来。不要查资料、看答案 —— 那样练不出临场表达能力。"
        />
        <div class="answer-actions">
          <span class="muted">{{ answer.trim().length }} 字</span>
          <el-button type="primary" :loading="thinking" :disabled="thinking" @click="submitAnswer">
            提交回答
          </el-button>
        </div>
      </div>

      <div v-if="report" class="card report">
        <div class="report-head">
          <div>
            <div class="rh-label">面试总评</div>
            <div class="rh-score" :class="scoreType(report.overallScore)">
              {{ report.overallScore ?? '—' }}<span class="rh-unit">分</span>
            </div>
          </div>
          <div class="rh-verdict">{{ report.verdict }}</div>
        </div>

        <div v-if="report.dimensions?.length" class="dims">
          <div v-for="d in report.dimensions" :key="d.name" class="dim">
            <div class="dim-top">
              <span>{{ d.name }}</span>
              <span class="dim-score">{{ d.score }}</span>
            </div>
            <el-progress :percentage="d.score" :show-text="false" :stroke-width="6" :status="d.score >= 60 ? 'success' : 'exception'" />
            <div class="dim-comment">{{ d.comment }}</div>
          </div>
        </div>

        <div v-if="report.strengths?.length" class="block">
          <h4>表现得好的地方</h4>
          <ul>
            <li v-for="(s, i) in report.strengths" :key="i">{{ s }}</li>
          </ul>
        </div>

        <div v-if="report.weaknesses?.length" class="block">
          <h4>需要补的地方</h4>
          <div v-for="(w, i) in report.weaknesses" :key="i" class="weak">
            <div class="weak-point">{{ w.point }}</div>
            <div class="weak-tip">{{ w.suggestion }}</div>
          </div>
        </div>

        <div v-if="report.suggestedTopics?.length" class="block">
          <h4>接下来优先复习</h4>
          <div class="topics">
            <el-tag v-for="t in report.suggestedTopics" :key="t" type="warning" effect="plain">{{ t }}</el-tag>
          </div>
        </div>

        <div class="report-actions">
          <el-button type="primary" @click="backToStart">再面一场</el-button>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.card + .card {
  margin-top: 16px;
}

.card h3 {
  margin: 0 0 14px;
  font-size: 15px;
  font-weight: 600;
}

.muted {
  color: #9ca3af;
  font-size: 12px;
}

.hint {
  color: #9ca3af;
  font-size: 12px;
  line-height: 1.6;
  margin-top: 4px;
}

.setup-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 14px;
  border-top: 1px solid var(--line);
}

.history {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.history-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  border: 1px solid var(--line);
  border-radius: 9px;
  cursor: pointer;
}

.history-item:hover {
  border-color: #c8d0e0;
  background: #fafbfd;
}

.hi-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.hi-role {
  font-size: 14px;
}

.hi-meta,
.hi-time {
  font-size: 12px;
  color: #9ca3af;
}

.hi-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.session-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.sh-role {
  font-size: 15px;
  font-weight: 600;
  margin-right: 10px;
}

.sh-meta {
  color: var(--ink-soft);
  font-size: 13px;
}

.sh-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.transcript {
  max-height: 56vh;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.turn {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.turn.candidate {
  align-items: flex-end;
}

.who {
  font-size: 12px;
  color: #9ca3af;
}

.bubble {
  max-width: 78%;
  padding: 11px 15px;
  border-radius: 10px;
  background: #f4f6fb;
  font-size: 14px;
  line-height: 1.7;
}

.turn.candidate .bubble {
  background: #eef3ff;
}

.bubble-text {
  white-space: pre-wrap;
  word-break: break-word;
}

.bubble.thinking {
  color: #9ca3af;
  font-style: italic;
}

.eval {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed #d8dfee;
}

.eval-text {
  font-size: 13px;
  color: var(--ink-soft);
  line-height: 1.6;
}

.answer-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
}

.report-head {
  display: flex;
  align-items: center;
  gap: 24px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--line);
}

.rh-label {
  font-size: 12px;
  color: var(--ink-soft);
  margin-bottom: 4px;
}

.rh-score {
  font-size: 34px;
  font-weight: 600;
  line-height: 1;
}

.rh-score.success { color: #2f9e63; }
.rh-score.warning { color: #c07a12; }
.rh-score.danger { color: #d64545; }

.rh-unit {
  font-size: 13px;
  font-weight: 400;
  margin-left: 4px;
}

.rh-verdict {
  flex: 1;
  font-size: 14px;
  line-height: 1.7;
}

.dims {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 16px;
  margin: 18px 0;
}

.dim-top {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  margin-bottom: 6px;
}

.dim-score {
  color: var(--ink-soft);
}

.dim-comment {
  margin-top: 6px;
  font-size: 12px;
  color: #9ca3af;
  line-height: 1.6;
}

.block {
  margin-top: 18px;
}

.block h4 {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
}

.block ul {
  margin: 0;
  padding-left: 20px;
  font-size: 13px;
  line-height: 1.8;
}

.weak {
  padding: 10px 14px;
  border-left: 3px solid #f0997b;
  background: #fdf8f5;
  border-radius: 0 8px 8px 0;
  margin-bottom: 8px;
}

.weak-point {
  font-size: 13px;
  margin-bottom: 4px;
}

.weak-tip {
  font-size: 12px;
  color: var(--ink-soft);
  line-height: 1.6;
}

.topics {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.report-actions {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--line);
}
</style>
