<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { practiceApi, questionApi } from '../api'
import { renderMarkdown } from '../utils/markdown'

const tasks = ref([])
const stats = ref(null)
const categories = ref([])
const expandedId = ref(null)
const answers = reactive({})
const drafts = reactive({})
const loading = ref(false)
const submitting = ref('')

const catMap = computed(() => {
  const map = {}
  categories.value.forEach((c) => { map[c.id] = c })
  return map
})

const today = computed(() => stats.value?.today || { total: 0, done: 0, mastered: 0, percent: 0 })

const trend = computed(() => {
  const list = [...(stats.value?.trend || [])].slice(0, 14).reverse()
  const max = Math.max(1, ...list.map((t) => t.total))
  return list.map((t) => ({
    ...t,
    height: Math.max(4, Math.round((t.total / max) * 100)),
    label: t.day.slice(5)
  }))
})

onMounted(load)

async function load() {
  loading.value = true
  try {
    const [taskList, stat, cats] = await Promise.all([
      practiceApi.today(),
      practiceApi.stats(),
      questionApi.categories()
    ])
    tasks.value = taskList
    stats.value = stat
    categories.value = cats
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function toggle(t) {
  if (expandedId.value === t.questionId) {
    expandedId.value = null
    return
  }
  expandedId.value = t.questionId
  if (t.mastery > 0 || answers[t.questionId]) {
    loadAnswer(t)
  }
}

async function loadAnswer(t) {
  if (answers[t.questionId]) return
  try {
    const q = await questionApi.detail(t.questionId)
    answers[t.questionId] = renderMarkdown(q.answer)
  } catch (e) {
    answers[t.questionId] = '<p class="muted">参考答案加载失败</p>'
  }
}

async function submit(t, mastery) {
  submitting.value = `${t.questionId}-${mastery}`
  try {
    const res = await practiceApi.submit({
      questionId: t.questionId,
      mastery,
      answerText: drafts[t.questionId] || null,
      costSeconds: null
    })
    t.mastery = mastery
    if (res?.answer && !answers[t.questionId]) {
      answers[t.questionId] = renderMarkdown(res.answer)
    }
    if (res?.todayProgress && stats.value) {
      stats.value.today = res.todayProgress
    }
    // 服务端已经用 SM-2 算好了这道题的下次复习时间，直接告诉用户「什么时候再见」——
    // 这是间隔重复能被用户感知到的唯一入口，否则算法在后台跑得再准也看不见。
    t.isNew = false
    t.isReview = true
    if (res?.intervalDays != null) t.intervalDays = res.intervalDays
    if (res?.nextReviewAt) t.nextReviewAt = res.nextReviewAt

    const label = mastery === 1 ? '已加入错题本' : mastery === 2 ? '标记为模糊' : '已掌握'
    ElMessage.success(`${label} · 下次复习 ${nextReviewText(res?.nextReviewAt)}`)
    if (mastery === 3 && expandedId.value === t.questionId) {
      expandedId.value = null
    }
    stats.value = await practiceApi.stats()
  } catch (e) {
    // 拦截器已提示
  } finally {
    submitting.value = ''
  }
}

function categoryName(id) {
  return catMap.value[id]?.name || '未分类'
}

function difficultyType(d) {
  return d === 1 ? 'info' : d === 3 ? 'danger' : 'warning'
}

function difficultyText(d) {
  return d === 1 ? '简单' : d === 3 ? '困难' : '中等'
}

function masteryLabel(m) {
  return m === 1 ? '不会' : m === 2 ? '模糊' : m === 3 ? '已掌握' : ''
}

function masteryType(m) {
  return m === 1 ? 'danger' : m === 2 ? 'warning' : 'success'
}

/** 把 yyyy-MM-dd 转成「9月19日」，比裸日期好读 */
function nextReviewText(dateStr) {
  if (!dateStr) return '稍后'
  const [, m, d] = dateStr.split('-')
  return `${Number(m)}月${Number(d)}日`
}
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <h2>今日题单</h2>
      <p>每天 10 道：按 SM-2 间隔重复算法挑出今天到期的复习题，再用新题补足，自动按分类均衡搭配。</p>
    </div>

    <div class="stats">
      <div class="card stat">
        <div class="label">今日进度</div>
        <div class="value">{{ today.done }}<span class="unit">/{{ today.total }}</span></div>
        <el-progress :percentage="today.percent" :show-text="false" :stroke-width="6" />
      </div>
      <div class="card stat">
        <div class="label">连续打卡</div>
        <div class="value">{{ stats?.streakDays ?? 0 }}<span class="unit">天</span></div>
        <div class="hint">今天还没开始也别断</div>
      </div>
      <div class="card stat">
        <div class="label">已掌握</div>
        <div class="value">{{ stats?.mastered ?? 0 }}<span class="unit">/ {{ stats?.answered ?? 0 }} 题</span></div>
        <div class="hint">掌握率 {{ stats?.masteryRate ?? 0 }}%</div>
      </div>
      <div class="card stat">
        <div class="label">题库覆盖</div>
        <div class="value">{{ stats?.coverage ?? 0 }}<span class="unit">%</span></div>
        <div class="hint">共 {{ stats?.bankTotal ?? 0 }} 题 · 待复习 {{ stats?.weak ?? 0 }} 题</div>
      </div>
    </div>

    <div v-if="(stats?.dueCount || 0) > today.total" class="due-banner">
      今天有 {{ stats.dueCount }} 道题到期，题单排得下 {{ today.total }} 道 —— 剩下的会顺延到明天。
    </div>

    <div class="tasks">
      <div
        v-for="t in tasks"
        :key="t.questionId"
        class="card task"
        :class="{ done: t.mastery > 0, active: expandedId === t.questionId }"
      >
        <div class="task-head" @click="toggle(t)">
          <span class="seq" :class="'m' + (t.mastery || 0)">{{ t.seq }}</span>
          <div class="task-title">{{ t.title }}</div>
          <div class="tags">
            <el-tag v-if="t.isNew" size="small" type="success" effect="plain">新题</el-tag>
            <el-tag v-else-if="t.isReview" size="small" type="danger" effect="plain">
              复习 · 间隔 {{ t.intervalDays || 1 }} 天
            </el-tag>
            <el-tag v-if="t.hot === 1" size="small" type="warning" effect="plain">高频</el-tag>
            <el-tag size="small" effect="plain">{{ categoryName(t.categoryId) }}</el-tag>
            <el-tag size="small" :type="difficultyType(t.difficulty)" effect="plain">
              {{ difficultyText(t.difficulty) }}
            </el-tag>
            <el-tag v-if="t.mastery > 0" size="small" :type="masteryType(t.mastery)" effect="dark">
              {{ masteryLabel(t.mastery) }}
            </el-tag>
            <el-icon class="arrow"><ArrowDown v-if="expandedId !== t.questionId" /><ArrowUp v-else /></el-icon>
          </div>
        </div>

        <div v-if="expandedId === t.questionId" class="task-body">
          <el-input
            v-model="drafts[t.questionId]"
            type="textarea"
            :rows="3"
            placeholder="先用大白话自己讲一遍（可跳过）。写下来才能发现「好像懂了」和「真会讲」之间的差距。"
          />

          <div class="body-actions">
            <el-button @click="loadAnswer(t)">
              <el-icon style="margin-right: 4px"><View /></el-icon>查看参考答案
            </el-button>
          </div>

          <div v-if="answers[t.questionId]" class="answer markdown-body" v-html="answers[t.questionId]" />

          <div class="rate">
            <span class="rate-label">
              这道题现在掌握到什么程度？
              <span class="sub">答完会按 SM-2 自动安排下次复习时间</span>
            </span>
            <el-button type="danger" plain :loading="submitting === `${t.questionId}-1`" @click="submit(t, 1)">
              不会，进错题本
            </el-button>
            <el-button type="warning" plain :loading="submitting === `${t.questionId}-2`" @click="submit(t, 2)">
              有点模糊
            </el-button>
            <el-button type="success" plain :loading="submitting === `${t.questionId}-3`" @click="submit(t, 3)">
              完全掌握
            </el-button>
          </div>
        </div>
      </div>

      <el-empty v-if="!loading && !tasks.length" description="题库还是空的，请检查后端种子数据是否导入成功" />
    </div>

    <div v-if="trend.length" class="card trend-card">
      <div class="trend-head">
        <h3>近 {{ trend.length }} 天刷题量</h3>
        <span class="muted">累计作答 {{ stats?.totalRecords ?? 0 }} 次</span>
      </div>
      <div class="trend">
        <div v-for="d in trend" :key="d.day" class="bar-wrap" :title="`${d.day} 共 ${d.total} 题，掌握 ${d.mastered} 题`">
          <div class="bar" :style="{ height: d.height + '%' }" />
          <span class="bar-label">{{ d.label }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 18px;
}

.stat {
  padding: 16px 18px;
}

.stat .label {
  color: var(--ink-soft);
  font-size: 12px;
  margin-bottom: 6px;
}

.stat .value {
  font-size: 26px;
  font-weight: 600;
  line-height: 1.2;
  margin-bottom: 8px;
}

.stat .unit {
  font-size: 12px;
  font-weight: 400;
  color: var(--ink-soft);
  margin-left: 5px;
}

.stat .hint {
  font-size: 12px;
  color: #9ca3af;
  margin-top: 6px;
}

.tasks {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.task {
  padding: 0;
  overflow: hidden;
  transition: border-color 0.15s;
}

.task.active {
  border-color: var(--brand);
}

.task-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 15px 18px;
  cursor: pointer;
}

.task.done .task-title {
  color: var(--ink-soft);
}

.seq {
  flex: none;
  width: 24px;
  height: 24px;
  border-radius: 7px;
  background: #eef0f5;
  color: var(--ink-soft);
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.seq.m1 { background: #fdeaea; color: #d64545; }
.seq.m2 { background: #fdf3e3; color: #c07a12; }
.seq.m3 { background: #e8f6ee; color: #2f9e63; }

.task-title {
  flex: 1;
  font-size: 14px;
  line-height: 1.5;
}

.tags {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
}

.arrow {
  color: #b6bcc8;
  margin-left: 2px;
}

.task-body {
  padding: 0 18px 18px;
  border-top: 1px solid var(--line);
  padding-top: 16px;
}

.body-actions {
  margin-top: 12px;
}

.answer {
  margin-top: 14px;
  padding: 16px 18px;
  background: #fafbfd;
  border: 1px solid var(--line);
  border-radius: 10px;
}

.rate {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px dashed var(--line);
  flex-wrap: wrap;
}

.rate-label {
  color: var(--ink-soft);
  font-size: 13px;
  margin-right: auto;
}

.rate-label .sub {
  display: block;
  font-size: 12px;
  color: #9ca3af;
  margin-top: 3px;
}

.due-banner {
  margin-bottom: 14px;
  padding: 11px 16px;
  border-radius: 10px;
  background: #fdf6e8;
  border: 1px solid #f0dcb4;
  color: #96601a;
  font-size: 13px;
  line-height: 1.6;
}

.trend-card {
  margin-top: 20px;
}

.trend-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 16px;
}

.trend-head h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.trend {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  height: 120px;
}

.bar-wrap {
  flex: 1;
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  align-items: center;
  gap: 6px;
}

.bar {
  width: 100%;
  background: linear-gradient(180deg, #6b8afd, #2f5cff);
  border-radius: 5px 5px 2px 2px;
  min-height: 4px;
}

.bar-label {
  font-size: 11px;
  color: #9ca3af;
}

@media (max-width: 900px) {
  .stats {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
