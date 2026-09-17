<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { practiceApi, questionApi } from '../api'
import { renderMarkdown } from '../utils/markdown'

const list = ref([])
const loading = ref(false)
const filter = ref(null)
const expandedId = ref(null)
const answers = reactive({})
const adding = ref('')

async function load() {
  loading.value = true
  try {
    list.value = await practiceApi.wrongBook({ mastery: filter.value ?? undefined })
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function toggle(item) {
  if (expandedId.value === item.questionId) {
    expandedId.value = null
    return
  }
  expandedId.value = item.questionId
  // 错题本接口已经带回答案，直接渲染
  if (!answers[item.questionId]) {
    answers[item.questionId] = renderMarkdown(item.answer)
  }
}

async function addToToday(item) {
  adding.value = String(item.questionId)
  try {
    await practiceApi.append(item.questionId)
    ElMessage.success('已加入今日题单，去「今日刷题」里练一遍')
  } finally {
    adding.value = ''
  }
}

function masteryType(m) {
  return m === 1 ? 'danger' : 'warning'
}

function masteryText(m) {
  return m === 1 ? '不会' : '模糊'
}

function diffType(d) {
  return d === 1 ? 'info' : d === 3 ? 'danger' : 'warning'
}

function diffText(d) {
  return d === 1 ? '简单' : d === 3 ? '困难' : '中等'
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h2>错题本</h2>
      <p>每道题只记录你最近一次的掌握状态。「不会」和「模糊」的题会自动进入每日题单优先复习。</p>
    </div>

    <div class="card filters">
      <el-radio-group v-model="filter" @change="load">
        <el-radio-button :value="null">全部待复习</el-radio-button>
        <el-radio-button :value="1">不会</el-radio-button>
        <el-radio-button :value="2">模糊</el-radio-button>
      </el-radio-group>
      <span class="muted count">共 {{ list.length }} 道</span>
      <el-button text @click="load">
        <el-icon style="margin-right: 4px"><Refresh /></el-icon>刷新
      </el-button>
    </div>

    <div v-loading="loading" class="list">
      <div
        v-for="item in list"
        :key="item.questionId"
        class="card item"
        :class="{ active: expandedId === item.questionId }"
      >
        <div class="item-head" @click="toggle(item)">
          <el-tag size="small" :type="masteryType(item.mastery)" effect="dark">
            {{ masteryText(item.mastery) }}
          </el-tag>
          <div class="item-title">{{ item.title }}</div>
          <div class="tags">
            <el-tag size="small" :type="diffType(item.difficulty)" effect="plain">
              {{ diffText(item.difficulty) }}
            </el-tag>
            <el-icon><ArrowDown v-if="expandedId !== item.questionId" /><ArrowUp v-else /></el-icon>
          </div>
        </div>
        <div v-if="expandedId === item.questionId" class="item-body">
          <div class="markdown-body" v-html="answers[item.questionId]" />
          <div class="body-actions">
            <el-button type="primary" plain :loading="adding === String(item.questionId)" @click="addToToday(item)">
              <el-icon style="margin-right: 4px"><Plus /></el-icon>加入今日题单再练一遍
            </el-button>
          </div>
        </div>
      </div>
      <el-empty v-if="!loading && !list.length" description="暂无错题。要么你都会了，要么还没开始刷。" />
    </div>
  </div>
</template>

<style scoped>
.filters {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 14px 16px;
  margin-bottom: 14px;
}

.count {
  font-size: 13px;
  margin-left: auto;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 140px;
}

.item {
  padding: 0;
  overflow: hidden;
}

.item.active {
  border-color: var(--brand);
}

.item-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 15px 18px;
  cursor: pointer;
}

.item-title {
  flex: 1;
  font-size: 14px;
  line-height: 1.5;
}

.tags {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
  color: #b6bcc8;
}

.item-body {
  padding: 16px 18px 18px;
  border-top: 1px solid var(--line);
  background: #fafbfd;
}

.body-actions {
  margin-top: 14px;
}
</style>
