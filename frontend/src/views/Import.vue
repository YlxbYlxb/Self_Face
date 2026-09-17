<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { questionApi } from '../api'

const mode = ref('paste')
const content = ref('')
const autoCreate = ref(true)
const importing = ref(false)
const result = ref(null)
const fileName = ref('')

const SAMPLE = JSON.stringify(
  [
    {
      title: 'Spring Bean 的生命周期有哪些阶段？',
      answer: '### 主要阶段\n\n1. 实例化\n2. 属性注入\n3. Aware 回调\n4. BeanPostProcessor 前置处理\n5. 初始化方法\n6. 后置处理\n7. 使用与销毁',
      category: 'spring',
      difficulty: 2,
      tags: 'Bean,生命周期',
      hot: 1
    },
    {
      title: 'MySQL 的索引为什么用 B+ 树而不是 B 树？',
      answer: '### 结论\n\nB+ 树的非叶子节点只存键，单个页能容纳更多键，树更矮、磁盘 IO 更少；叶子节点用链表串联，范围查询效率高。',
      category: 'mysql',
      difficulty: 2,
      tags: '索引,B+树',
      hot: 1
    }
  ],
  null,
  2
)

// 边输边解析，让格式问题在提交之前就暴露出来
const parsed = computed(() => {
  const text = content.value.trim()
  if (!text) {
    return { ok: false, empty: true }
  }
  let data
  try {
    data = JSON.parse(text)
  } catch (e) {
    return { ok: false, message: `JSON 格式有误：${e.message}` }
  }
  if (Array.isArray(data)) {
    return { ok: true, questions: data.length, categories: 0, shape: '题目数组' }
  }
  if (data && typeof data === 'object') {
    const questions = Array.isArray(data.questions) ? data.questions.length : 0
    const categories = Array.isArray(data.categories) ? data.categories.length : 0
    if (questions === 0) {
      return { ok: false, message: '对象里没有找到非空的 questions 数组' }
    }
    return { ok: true, questions, categories, shape: '含 categories 的对象' }
  }
  return { ok: false, message: '顶层需要是题目数组，或包含 questions 字段的对象' }
})

const canSubmit = computed(() => parsed.value.ok && parsed.value.questions > 0 && !importing.value)

function onFileChange(uploadFile) {
  const raw = uploadFile?.raw
  if (!raw) {
    return
  }
  if (raw.size > 5 * 1024 * 1024) {
    ElMessage.error('文件超过 5MB，请拆分后再导入')
    return
  }
  const reader = new FileReader()
  reader.onload = () => {
    content.value = String(reader.result || '')
    fileName.value = raw.name
    ElMessage.success(`已读取 ${raw.name}`)
  }
  reader.onerror = () => ElMessage.error('文件读取失败，请确认是 UTF-8 编码的 .json')
  reader.readAsText(raw, 'utf-8')
}

function fillSample() {
  content.value = SAMPLE
  fileName.value = ''
}

function clearAll() {
  content.value = ''
  fileName.value = ''
  result.value = null
}

async function submit() {
  if (!canSubmit.value) {
    return
  }
  importing.value = true
  result.value = null
  try {
    const res = await questionApi.importQuestions({
      content: content.value,
      autoCreateCategory: autoCreate.value
    })
    result.value = res
    if (res.inserted > 0) {
      ElMessage.success(`成功导入 ${res.inserted} 道题`)
    } else {
      ElMessage.warning('没有新增题目，请检查是否都已存在')
    }
  } catch (e) {
    // 具体错误提示已由请求拦截器统一弹出
  } finally {
    importing.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h2>导入题库</h2>
      <p>
        支持粘贴 JSON 或上传 .json 文件，单次上限 500 道。题目按标题去重，
        同一份文件重复导入只会补进新增的部分，不会产生重复题。
      </p>
    </div>

    <div class="card">
      <el-radio-group v-model="mode" style="margin-bottom: 14px">
        <el-radio-button value="paste">粘贴 JSON</el-radio-button>
        <el-radio-button value="upload">上传文件</el-radio-button>
      </el-radio-group>

      <el-input
        v-if="mode === 'paste'"
        v-model="content"
        type="textarea"
        :rows="14"
        resize="vertical"
        class="mono"
        placeholder='支持两种写法：顶层直接是题目数组，或是 { "categories": [...], "questions": [...] }。点右下角「填入示例」可以看格式。'
      />

      <el-upload
        v-else
        drag
        :auto-upload="false"
        :show-file-list="false"
        accept=".json,application/json"
        :on-change="onFileChange"
      >
        <div class="upload-hint">
          <div class="upload-title">把 .json 文件拖到这里，或点击选择</div>
          <div class="muted">文件内容会读进下方的预览区，确认无误后再提交</div>
        </div>
      </el-upload>

      <div class="preview" :class="{ bad: !parsed.ok && !parsed.empty, good: parsed.ok }">
        <template v-if="parsed.empty">
          <span class="muted">等待输入内容…</span>
        </template>
        <template v-else-if="parsed.ok">
          <el-icon><CircleCheck /></el-icon>
          <span>
            已识别 <b>{{ parsed.questions }}</b> 道题目
            <template v-if="parsed.categories > 0">、<b>{{ parsed.categories }}</b> 个分类定义</template>
            （{{ parsed.shape }}）
          </span>
        </template>
        <template v-else>
          <el-icon><WarningFilled /></el-icon>
          <span>{{ parsed.message }}</span>
        </template>
      </div>

      <div class="actions">
        <el-checkbox v-model="autoCreate">遇到未知分类时自动创建</el-checkbox>
        <div class="buttons">
          <el-button @click="fillSample">填入示例</el-button>
          <el-button @click="clearAll">清空</el-button>
          <el-button type="primary" :loading="importing" :disabled="!canSubmit" @click="submit">
            开始导入
          </el-button>
        </div>
      </div>
    </div>

    <div v-if="result" class="card result">
      <h3>导入结果</h3>
      <div class="stats">
        <div class="stat">
          <div class="label">提交</div>
          <div class="value">{{ result.total }}</div>
        </div>
        <div class="stat">
          <div class="label">写入</div>
          <div class="value ok">{{ result.inserted }}</div>
        </div>
        <div class="stat">
          <div class="label">跳过</div>
          <div class="value">{{ result.skipped }}</div>
        </div>
        <div class="stat">
          <div class="label">新建分类</div>
          <div class="value">{{ result.categoriesCreated }}</div>
        </div>
      </div>

      <div v-if="result.reasons && result.reasons.length" class="reasons">
        <div class="reasons-title">跳过明细</div>
        <ul>
          <li v-for="(r, i) in result.reasons" :key="i">{{ r }}</li>
        </ul>
      </div>

      <div v-if="result.inserted > 0" class="next">
        <el-button type="primary" link @click="$router.push('/questions')">
          去题库看看
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.upload-hint {
  padding: 24px 0;
}

.upload-title {
  font-size: 14px;
  color: var(--ink);
  margin-bottom: 6px;
}

.preview {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
  padding: 10px 14px;
  border-radius: 10px;
  background: #f7f8fc;
  border: 1px solid var(--line);
  font-size: 13px;
  color: var(--ink-soft);
}

.preview.good {
  background: #f0f9f2;
  border-color: #cde9d4;
  color: #2f7a44;
}

.preview.bad {
  background: #fdf3f3;
  border-color: #f3d1d1;
  color: #b03a3a;
}

.actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 16px;
}

.buttons {
  display: flex;
  gap: 8px;
}

.result {
  margin-top: 18px;
}

.result h3 {
  margin: 0 0 14px;
  font-size: 15px;
  font-weight: 600;
}

.stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
  gap: 12px;
}

.stat {
  background: #f7f8fc;
  border-radius: 10px;
  padding: 12px 14px;
}

.stat .label {
  font-size: 12px;
  color: var(--ink-soft);
  margin-bottom: 4px;
}

.stat .value {
  font-size: 20px;
  font-weight: 600;
}

.stat .value.ok {
  color: #2f7a44;
}

.reasons {
  margin-top: 16px;
}

.reasons-title {
  font-size: 13px;
  font-weight: 500;
  margin-bottom: 6px;
}

.reasons ul {
  margin: 0;
  padding-left: 20px;
  color: var(--ink-soft);
  font-size: 13px;
  line-height: 1.8;
}

.next {
  margin-top: 12px;
}
</style>
