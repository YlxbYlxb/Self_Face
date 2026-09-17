<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { jdApi, practiceApi } from '../api'

const jdText = ref('')
const role = ref('')
const analyzing = ref(false)
const importing = ref(false)
const result = ref(null)
const selected = ref(new Set())

const hitKeywords = computed(() =>
  (result.value?.keywords || []).filter((k) => k.matchCount > 0))
const missKeywords = computed(() =>
  (result.value?.keywords || []).filter((k) => k.matchCount === 0))
const allSelected = computed(() =>
  !!result.value?.recommended?.length && selected.value.size === result.value.recommended.length)

async function analyze() {
  if (jdText.value.trim().length < 30) {
    ElMessage.warning('请把完整的岗位描述粘进来（至少 30 个字）')
    return
  }
  analyzing.value = true
  result.value = null
  selected.value = new Set()
  try {
    const res = await jdApi.analyze({ jdText: jdText.value, role: role.value || null })
    result.value = res
    // 默认全选：多数人就是想把这些题都练一遍
    selected.value = new Set((res.recommended || []).map((q) => q.questionId))
  } catch (e) {
    // 拦截器已提示
  } finally {
    analyzing.value = false
  }
}

function toggle(id) {
  const next = new Set(selected.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  selected.value = next
}

function toggleAll() {
  const all = result.value?.recommended || []
  selected.value = allSelected.value ? new Set() : new Set(all.map((q) => q.questionId))
}

async function importSelected() {
  if (!selected.value.size) {
    ElMessage.warning('请先勾选要加入的题目')
    return
  }
  importing.value = true
  try {
    const res = await practiceApi.appendBatch([...selected.value])
    const skipped = selected.value.size - res.inserted
    ElMessage.success(`已加入 ${res.inserted} 道题到今日题单`
      + (skipped > 0 ? `，${skipped} 道本来就在题单里` : ''))
  } catch (e) {
    // 拦截器已提示
  } finally {
    importing.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h2>JD 定向题单</h2>
      <p>
        把目标岗位的 JD 整段粘进来，会先抽出技术要求，再从题库里找出对应的题目。
        抽出来却没有对应题目的技术点，就是你的题库还没覆盖到的地方。
      </p>
    </div>

    <div class="card input-card">
      <el-input
        v-model="role"
        class="role-input"
        placeholder="目标岗位（可选，例如：Java 后端开发实习生）"
        clearable
      />
      <el-input
        v-model="jdText"
        type="textarea"
        :rows="9"
        resize="vertical"
        placeholder="把招聘网站上的岗位描述整段复制过来，尤其是「任职要求」里的技术栈部分"
      />
      <div class="actions">
        <span class="muted">{{ jdText.trim().length }} 字</span>
        <el-button type="primary" :loading="analyzing" @click="analyze">
          {{ analyzing ? '正在分析…' : '分析这份 JD' }}
        </el-button>
      </div>
    </div>

    <template v-if="result">
      <div class="card overview">
        <div class="ov-left">
          <div class="ov-label">识别出的岗位</div>
          <div class="ov-role">{{ result.role }}</div>
          <div class="ov-summary">{{ result.summary || '—' }}</div>
        </div>
        <div class="ov-right">
          <el-progress type="circle" :percentage="result.coverage" :width="88" />
          <div class="ov-hint">
            题库覆盖 {{ result.coveredCount }}/{{ result.keywordCount }} 个技术要求
          </div>
        </div>
      </div>

      <div class="card">
        <h3>技术要求清单</h3>
        <div class="kw-group">
          <span class="kw-label">题库里有题</span>
          <div class="kw-list">
            <el-tag v-for="k in hitKeywords" :key="k.term" type="success" effect="plain">
              {{ k.term }} · {{ k.matchCount }} 题
            </el-tag>
            <span v-if="!hitKeywords.length" class="muted">无</span>
          </div>
        </div>
        <div class="kw-group">
          <span class="kw-label">题库没覆盖</span>
          <div class="kw-list">
            <el-tag v-for="k in missKeywords" :key="k.term" type="danger" effect="plain">
              {{ k.term }}
            </el-tag>
            <span v-if="!missKeywords.length" class="muted">这份 JD 的技术点都覆盖到了</span>
          </div>
        </div>
        <p class="kw-note">
          「没覆盖」只代表题库里没有对应题目，不代表你不用管它 —— 这些点可以在
          <strong>题库导入</strong>页补充进来。
        </p>
      </div>

      <div class="card">
        <div class="rec-head">
          <h3>推荐题单 · {{ result.recommended.length }} 道</h3>
          <div class="rec-actions">
            <el-button v-if="result.recommended.length" link @click="toggleAll">
              {{ allSelected ? '取消全选' : '全选' }}
            </el-button>
            <el-button
              type="primary"
              :loading="importing"
              :disabled="!selected.size"
              @click="importSelected"
            >
              加入今日题单（{{ selected.size }}）
            </el-button>
          </div>
        </div>

        <div class="rec-list">
          <div
            v-for="q in result.recommended"
            :key="q.questionId"
            class="rec-item"
            :class="{ on: selected.has(q.questionId) }"
            @click="toggle(q.questionId)"
          >
            <el-checkbox
              :model-value="selected.has(q.questionId)"
              @click.stop
              @change="toggle(q.questionId)"
            />
            <div class="rec-title">{{ q.title }}</div>
            <div class="rec-tags">
              <el-tag v-for="t in q.hitTerms" :key="t" size="small" type="success" effect="plain">
                {{ t }}
              </el-tag>
              <el-tag v-if="q.hot === 1" size="small" type="warning" effect="plain">高频</el-tag>
            </div>
          </div>

          <el-empty
            v-if="!result.recommended.length"
            description="这份 JD 的技术点暂时没在题库里找到对应题目"
          />
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.input-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.role-input {
  max-width: 380px;
}

.actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.muted {
  color: #9ca3af;
  font-size: 12px;
}

.overview {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-top: 16px;
}

.ov-label {
  color: var(--ink-soft);
  font-size: 12px;
  margin-bottom: 4px;
}

.ov-role {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 6px;
}

.ov-summary {
  color: var(--ink-soft);
  font-size: 13px;
  line-height: 1.6;
}

.ov-right {
  flex: none;
  text-align: center;
}

.ov-hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--ink-soft);
}

.card + .card {
  margin-top: 16px;
}

.card h3 {
  margin: 0 0 14px;
  font-size: 15px;
  font-weight: 600;
}

.kw-group {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}

.kw-label {
  flex: none;
  width: 84px;
  padding-top: 3px;
  color: var(--ink-soft);
  font-size: 12px;
}

.kw-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.kw-note {
  margin: 6px 0 0;
  padding-top: 12px;
  border-top: 1px dashed var(--line);
  color: #9ca3af;
  font-size: 12px;
  line-height: 1.6;
}

.rec-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.rec-head h3 {
  margin: 0;
}

.rec-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.rec-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.rec-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 14px;
  border: 1px solid var(--line);
  border-radius: 9px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}

.rec-item:hover {
  border-color: #c8d0e0;
}

.rec-item.on {
  border-color: var(--brand);
  background: #f7f9ff;
}

.rec-title {
  flex: 1;
  font-size: 14px;
  line-height: 1.5;
}

.rec-tags {
  flex: none;
  display: flex;
  gap: 5px;
}
</style>
