<script setup>
import { onMounted, reactive, ref } from 'vue'
import { questionApi } from '../api'
import { renderMarkdown } from '../utils/markdown'

const categories = ref([])
const activeCat = ref(null)
const keyword = ref('')
const difficulty = ref(null)
const onlyHot = ref(false)

const list = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)

const dialogVisible = ref(false)
const current = ref(null)
const currentAnswer = ref('')
const answerLoading = ref(false)

onMounted(async () => {
  categories.value = await questionApi.categories()
  load()
})

async function load() {
  loading.value = true
  try {
    const res = await questionApi.page({
      categoryId: activeCat.value ?? undefined,
      keyword: keyword.value || undefined,
      difficulty: difficulty.value ?? undefined,
      onlyHot: onlyHot.value || undefined,
      page: page.value,
      size: size.value
    })
    list.value = res.records || []
    total.value = res.total || 0
  } finally {
    loading.value = false
  }
}

function selectCat(id) {
  activeCat.value = activeCat.value === id ? null : id
  page.value = 1
  load()
}

function reset() {
  keyword.value = ''
  difficulty.value = null
  onlyHot.value = false
  activeCat.value = null
  page.value = 1
  load()
}

async function open(q) {
  current.value = q
  currentAnswer.value = ''
  dialogVisible.value = true
  answerLoading.value = true
  try {
    const detail = await questionApi.detail(q.id)
    currentAnswer.value = renderMarkdown(detail.answer)
  } finally {
    answerLoading.value = false
  }
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
      <h2>题库</h2>
      <p>共 {{ total }} 道题。列表默认隐藏答案——先自己想，再点开对照。</p>
    </div>

    <div class="layout">
      <aside class="card cats">
        <div class="cats-title">分类</div>
        <div class="cat" :class="{ on: activeCat === null }" @click="selectCat(null)">
          <span>全部分类</span>
        </div>
        <div
          v-for="c in categories"
          :key="c.id"
          class="cat"
          :class="{ on: activeCat === c.id }"
          @click="selectCat(c.id)"
        >
          <span>{{ c.name }}</span>
          <em>{{ c.count }}</em>
        </div>
      </aside>

      <section class="main">
        <div class="card filters">
          <el-input
            v-model="keyword"
            placeholder="搜索题干或标签，例如 HashMap、MVCC、三次握手"
            clearable
            style="flex: 1"
            @keyup.enter="load"
            @clear="load"
          />
          <el-select v-model="difficulty" placeholder="难度" clearable style="width: 110px" @change="load">
            <el-option label="简单" :value="1" />
            <el-option label="中等" :value="2" />
            <el-option label="困难" :value="3" />
          </el-select>
          <el-checkbox v-model="onlyHot" @change="load">仅高频</el-checkbox>
          <el-button type="primary" @click="load">搜索</el-button>
          <el-button @click="reset">重置</el-button>
        </div>

        <div v-loading="loading" class="list">
          <div v-for="q in list" :key="q.id" class="card item" @click="open(q)">
            <div class="item-main">
              <div class="item-title">{{ q.title }}</div>
              <div class="item-tags">
                <el-tag v-if="q.hot === 1" size="small" type="warning" effect="plain">高频</el-tag>
                <el-tag size="small" :type="diffType(q.difficulty)" effect="plain">
                  {{ diffText(q.difficulty) }}
                </el-tag>
                <span v-if="q.tags" class="muted tag-text">{{ q.tags }}</span>
              </div>
            </div>
            <el-icon class="go"><ArrowRight /></el-icon>
          </div>
          <el-empty v-if="!loading && !list.length" description="没有匹配的题目" />
        </div>

        <div class="pager">
          <el-pagination
            v-model:current-page="page"
            :page-size="size"
            :total="total"
            layout="prev, pager, next, total"
            background
            @current-change="load"
          />
        </div>
      </section>
    </div>

    <el-dialog v-model="dialogVisible" :title="current?.title" width="720px" top="6vh">
      <div class="dialog-meta">
        <el-tag v-if="current?.hot === 1" size="small" type="warning" effect="plain">高频</el-tag>
        <el-tag size="small" :type="diffType(current?.difficulty)" effect="plain">
          {{ diffText(current?.difficulty) }}
        </el-tag>
        <span class="muted">{{ current?.tags }}</span>
      </div>
      <div v-loading="answerLoading" class="markdown-body dialog-answer" v-html="currentAnswer" />
    </el-dialog>
  </div>
</template>

<style scoped>
.layout {
  display: grid;
  grid-template-columns: 200px 1fr;
  gap: 16px;
  align-items: start;
}

.cats {
  padding: 14px 12px;
  position: sticky;
  top: 0;
}

.cats-title {
  font-size: 12px;
  color: var(--ink-soft);
  padding: 0 8px 8px;
}

.cat {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  color: #4b5563;
  transition: background 0.12s;
}

.cat:hover {
  background: #f5f6fa;
}

.cat.on {
  background: var(--brand-soft);
  color: var(--brand);
  font-weight: 500;
}

.cat em {
  font-style: normal;
  font-size: 11px;
  color: #9ca3af;
}

.main {
  min-width: 0;
}

.filters {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 120px;
}

.item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 18px;
  cursor: pointer;
  transition: border-color 0.15s, transform 0.1s;
}

.item:hover {
  border-color: var(--brand);
}

.item-main {
  flex: 1;
  min-width: 0;
}

.item-title {
  font-size: 14px;
  line-height: 1.55;
  margin-bottom: 6px;
}

.item-tags {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.tag-text {
  font-size: 12px;
}

.go {
  color: #c3c8d2;
  flex: none;
}

.pager {
  display: flex;
  justify-content: center;
  margin-top: 18px;
}

.dialog-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
}

.dialog-answer {
  max-height: 62vh;
  overflow-y: auto;
  padding-right: 6px;
}

@media (max-width: 900px) {
  .layout {
    grid-template-columns: 1fr;
  }
  .cats {
    position: static;
  }
}
</style>
