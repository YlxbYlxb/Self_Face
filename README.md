# SelfFace · 面试准备平台

一个面向校招/实习求职者的八股刷题 + 简历分析平台。五个核心能力：

1. **题库** —— 内置 **1294 道**高频八股题，覆盖 Java 基础、集合、并发、JVM、Spring、MySQL、Redis、计算机网络、操作系统、算法、项目场景、**分布式与微服务**、**消息队列与性能优化**、**AI 与大模型**共 **14 个分类**。每题都有结构化的答题要点（不是一句话答案），并按难度（易/中/难）与高频标记分层。支持从 JSON 批量导入扩充，不必改代码。
2. **间隔重复调度（SM-2）** —— 这是这个项目和其他刷题站最不一样的地方。每道题按你的自评维护「下次该复习的时间」，答得越熟间隔拉得越长（1 → 3 → 8 → 22 → 60 天）；答错就归零重来。每天题单先取**到期**的复习题，再补新题，而不是「错题一直刷到对」。
3. **每日刷题与记录** —— 每天自动组卷 10 题，记录每次作答的掌握程度、你口述的答案与耗时；配套连击天数、掌握率、题库覆盖率、近 14 天趋势、错题本。
4. **JD 定向题单** —— 贴一段岗位 JD，抽取技术关键词 → 在题库里召回 → 算出**覆盖率**并列出题库覆盖不到的技术点 → 一键把命中的题生成今日题单。
5. **简历分析 + 模拟面试** —— 上传 PDF/DOCX 简历，调用**你自己配置的大模型**，两阶段产出结构化画像与「大概率被问到」的问题清单（每题带考察意图、追问链和答题要点）；再基于这份画像开一场**模拟面试**：面试官多轮追问、逐轮打分点评，结束后给报告与建议复习的题目。分析在服务端后台执行，上传立刻返回任务 id，前端轮询结果。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 21 · Spring Boot 3.3 · Spring Security + JWT · MyBatis-Plus 3.5 |
| 数据库 | MySQL 8（建表脚本自动执行，库表无需手工创建） |
| 文档解析 | Apache PDFBox 3（PDF）· Apache POI 5（DOCX） |
| 前端 | Vue 3 · Vite 5 · Element Plus · Pinia · Vue Router · markdown-it |
| 大模型 | 任意 OpenAI 协议兼容服务（DeepSeek / OpenAI / 通义 / Kimi / 智谱 / 本地 Ollama） |

## 目录结构

```
SelfFace/
├── backend/                          Spring Boot 服务（默认端口 8081）
│   ├── run.cmd                       Windows 一键启动脚本
│   └── src/
│       ├── main/java/com/selfface/
│       │   ├── common/               统一响应 R、业务异常、全局异常处理、任务状态常量
│       │   ├── config/               安全配置、MyBatis-Plus、异步线程池、题库种子导入器
│       │   ├── controller/           Auth / Question / Practice / Resume / JdMatch / Interview / LlmSetting
│       │   ├── entity/ mapper/       10 张表（统计类查询用注解 SQL，聚合下沉到数据库）
│       │   ├── llm/                  LlmClient（OpenAI 兼容）+ ResumeAnalyzer / JdAnalyzer / InterviewAgent
│       │   │                         + LlmJson（模型输出 JSON 容错解析，三个 Agent 共用）
│       │   ├── resume/               PDF / DOCX 文本抽取
│       │   ├── security/             JWT 工具、鉴权过滤器、限流拦截器、登录防爆破、API Key 加解密
│       │   └── service/              Sm2Scheduler 间隔重复调度、DailyTaskGenerator 组卷、
│       │                             ReviewStateService 复习状态、JdMatchService JD 召回、
│       │                             InterviewService 面试编排、ResumeAnalysisJob 后台分析
│       ├── test/java/                78 个单元测试
│       └── resources/
│           ├── application.yml       配置（数据库、JWT、种子开关、限流阈值）
│           ├── application-prod.yml  生产覆盖（日志收敛、SQL 初始化静默）
│           ├── db/schema.sql         建表脚本，启动时自动执行
│           └── seed/*.json           题库种子，按标题去重增量导入
│                                     （01~03 为自有题目，04 由开源文档提取，见 THIRD-PARTY-NOTICES.md）
├── frontend/                         Vue 3 应用（默认端口 5273）
│   ├── run.cmd                       Windows 一键启动脚本
│   └── src/
│       ├── api/                      接口封装与拦截器
│       ├── router/ stores/           路由守卫、登录态
│       └── views/
│           ├── Login.vue             登录 / 注册
│           ├── Dashboard.vue         今日刷题（标注新题 / 复习题 + 下次复习时间）+ 统计 + 趋势
│           ├── Questions.vue         题库浏览与检索
│           ├── WrongBook.vue         错题本
│           ├── JdMatch.vue           贴 JD → 抽关键词 → 覆盖率 → 一键生成定向题单
│           ├── MockInterview.vue     模拟面试对话（面试官追问 + 打分点评）+ 结题报告
│           ├── Import.vue            题库导入（粘贴 JSON / 上传文件 + 预览校验）
│           ├── Resume.vue            简历分析（提交后轮询进度）
│           └── Settings.vue          LLM 配置 + 个人资料
├── scripts/                          质量门禁与测试工具
│   ├── check_seed.py                 校验题库种子字段完整性
│   ├── import-javaguide.py           从开源文档批量生成题库（可复现的提取管道）
│   ├── check_security.py             拦截默认密钥 / 明文 Key 等安全红线
│   ├── mock_llm.py                   本地假模型服务，用于无 Key 跑通 AI 全链路
│   └── cleanup-verify-data.sh        清理验证脚本产生的测试数据
├── smoke_test.py                     端到端冒烟测试（22 项）
├── verify_async_resume.py            异步分析链路验证（无需真实 Key）
├── verify_inflight_guard.py          并发守卫验证
├── verify_new_features.py            安全加固 / 题库导入 / 限流验证（16 项）
├── verify_srs.py                     间隔重复调度验证（19 项）
├── verify_jd.py                      JD 定向题单验证（20 项）
└── verify_interview.py               模拟面试闭环验证（24 项）
```

> 端口说明：本机 8080 与 5173 常被其他项目占用，因此本项目固定用 **后端 8081 / 前端 5273**，
> 可用环境变量 `SERVER_PORT` 覆盖后端端口。

## 快速开始

### 前置要求

- JDK 21+
- Maven 3.9+
- MySQL 8（本机已运行即可）
- Node.js 18+

### 1. 启动后端

**首次运行先建配置文件**：把 `backend/.env.local.example` 复制为 `backend/.env.local`，填入 MySQL 密码。该文件已在 `.gitignore` 中，不会被提交，仓库里也不会出现真实口令。

```bash
cd backend
cp .env.local.example .env.local      # 然后编辑，填 DB_PASSWORD
```

**Windows 直接双击 `backend/run.cmd`**（会自动读取 `.env.local`，无需手工设环境变量），或手动执行：

```bash
cd backend
export DB_PASSWORD=你的MySQL密码      # 其余用默认值
mvn spring-boot:run
```

数据库相关的环境变量（都有默认值，按需覆盖）：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `DB_HOST` | `localhost` | |
| `DB_PORT` | `3306` | |
| `DB_NAME` | `selfface` | 不存在会自动创建 |
| `DB_USERNAME` | `root` | |
| `DB_PASSWORD` | 空 | **必须填** |
| `SERVER_PORT` | `8081` | |
| `JWT_SECRET` | 内置开发值 | 上线请务必替换 |

启动成功后会在日志里看到：

```
题库就绪：本次新增分类 14 个、题目 1294 道，现共 1294 道
```

### 2. 启动前端

```bash
cd frontend
npm install      # 首次
npm run dev      # 或双击 run.cmd
```

打开 http://localhost:5273 ，注册一个账号即可开始。

> 前端通过 Vite 代理把 `/api` 转发到 `localhost:8081`，所以不存在跨域问题。

### 3. 配置大模型（使用 AI 功能前必做）

登录后进入「设置」，点一个服务商的快捷按钮填入地址与模型名，再粘上你自己的 API Key：

| 服务 | Base URL | 模型示例 |
|---|---|---|
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` |
| Kimi | `https://api.moonshot.cn/v1` | `moonshot-v1-8k` |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash` |
| 本地 Ollama | `http://localhost:11434/v1` | `qwen2.5:7b` |

保存后点「测试连接」确认能通。API Key 存进数据库前会用 AES-256-GCM 加密（密钥来自环境变量
`APP_CRYPTO_KEY`），接口永不回传明文，页面刷新后只显示掩码。

没配 Key 也能正常刷题和复习调度，只有三个 AI 功能（简历分析 / JD 定向题单 / 模拟面试）需要。

## 测试

```bash
# 单元测试：78 项，不需要数据库
cd backend && mvn test

# 端到端冒烟测试：22 项，需要后端已启动
python smoke_test.py

# 异步分析链路：上传非阻塞 → 状态流转 → 失败落库 → 终态后可重提
python verify_async_resume.py

# 并发守卫：需要先往 resume_analysis 插一条 RUNNING 记录
python verify_inflight_guard.py <username>

# 安全加固 / 题库导入 / 限流：16 项
python verify_new_features.py

# 间隔重复调度：自评推进间隔、EF 上下限、到期筛选（19 项）
python verify_srs.py

# JD 定向题单：抽词 → 召回 → 覆盖率 → 一键加题单（20 项）
python verify_jd.py

# 模拟面试闭环：多轮追问 → 评分报告 → 幂等 / 越权（24 项）
python verify_interview.py

# 质量门禁（CI 可直接用，退出码非 0 即失败）
python scripts/check_seed.py        # 题库种子字段完整性
python scripts/check_security.py    # 默认密钥 / 明文 Key 等安全红线
```

> **跑 AI 相关验证不需要真实 API Key**：`scripts/mock_llm.py` 起一个 OpenAI 协议兼容的
> 假模型服务（默认 18080），在设置页把 Base URL 指过去即可把全链路跑通。验证脚本结束会自行清理测试数据。

## 核心机制说明

### 间隔重复调度（SM-2 简化版）

这是这个项目的核心算法。**题单不是「错题优先」，而是「到期复习优先」**——有效复习的时机是
「快忘的时候再见到它」，而不是「一直刷到记住」。

`Sm2Scheduler` 是纯函数，输入「上次状态 + 本次自评」，输出「新的间隔 / 难度系数 / 下次复习日」：

| 自评 | 间隔推进 | 难度系数 EF | 副作用 |
|---|---|---|---|
| 不会 | 归零重来（1 天） | −0.20 | `lapse_count +1` |
| 模糊 | `max(1, round(间隔 × 1.2))` | −0.15 | — |
| 掌握 | `max(1, round(间隔 × EF))` | +0.10 | — |

EF 夹在 `[1.30, 2.80]`：低于 1.3 会让间隔越排越短（等于惩罚用户），高于 2.8 会让间隔膨胀过快。

连续三次答「掌握」的间隔走势是 **1 → 3 → 8 天**，再往后 22 → 60 天。每个用户每道题的
状态落在 `review_state` 表（`user_id + question_id` 唯一），存 `last_reviewed_at`、
`next_review_at`、`interval_days`、`ease_factor`、`review_count`、`lapse_count`。

**为什么单独一张表而不是往 `practice_record` 加字段**：`practice_record` 是流水（一次作答一行，
只增不改），`review_state` 是快照（每题一行，每次作答更新）。混在一起会让「取每题最新状态」
这种查询退化成 `GROUP BY + MAX(id)` 的子查询。

### 每日组卷算法

`DailyTaskGenerator.pickQuestions()` 分三步：

1. **到期复习题优先**（最多 5 道）：`next_review_at <= 今天` 的题，按
   「逾期最久 → EF 最低 → 遗忘次数最多」排序。EF 低的题说明它对这个人偏难，值得优先加固。
2. **新题补充**：从未做过的题里按高频标记优先取，补足 10 道。
3. **兜底**：题库做空时，取「最久没碰过」的题（按 `review_state.last_reviewed_at` 升序），
   而不是按 `id` 从头轮一遍。

分类均衡贯穿其中：按分类轮流取，避免一天十道全是 MySQL。

当天题单落在 `daily_task` 表，唯一键 `(user_id, task_date, question_id)` 配合 `INSERT IGNORE` 批量写入，
保证重复刷新、并发请求都不会重复组卷或报错。

**为什么生成器要单独一个类**：事务边界要落在「组卷 + 落库」这一小段上；而读取端必须运行在事务之外，
生成结束后再查一次才是新快照，否则在 REPEATABLE READ 下会一直读到空。

### JD 定向题单

贴一段岗位 JD，产出「这份 JD 我该刷哪些题」：

```
POST /api/jd/analyze  → LLM 抽取结构化技能关键词（要求 JSON 输出，解析失败有兜底）
                      → 关键词在题库里召回（title / tags / answer 模糊匹配）
                      → 算覆盖率：JD 要求的技术点里，题库能覆盖多少
                      → 一键把命中的题追加进今日题单（daily_task.source = 'JD'）
```

**覆盖率是这里最有价值的数字**：它把「我简历和这个岗位差在哪」变成可量化的百分比，
列出题库覆盖不到的技术点（说明是真短板，不是没刷到）。

模型输出的 JSON 容错解析抽到了 `LlmJson`（剥代码块围栏 → 截取首尾花括号 → 失败抛可读错误），
简历分析、JD 分析、模拟面试三处共用，避免以后修 bug 要改三遍。

### 模拟面试闭环

```
POST /api/interview/start        → 基于已解析的简历画像 + 岗位 / 类型，面试官提出开场问题
POST /api/interview/answer       → 记录作答 → 面试官追问 + 打分 + 点评（只提问，不解答）
POST /api/interview/{id}/finish  → 生成报告：总分、分维度评价、最差 3 题、建议复习的题目
```

关键约束是 **`InterviewAgent` 只提问不解答**：用户答错也不能给答案，否则失去练习价值。
报告里的「建议复习题目」会关联回题库 id，形成「面试暴露短板 → 回题单强化」的闭环。

`finish` 幂等（重复调用不重复烧 token），已结束的会话拒绝继续作答，越权访问返回 404 而非 403
（不暴露资源是否存在）。

### 简历分析 Agent 的两阶段设计

`ResumeAnalyzer` 拆成两次模型调用，而不是一次到位：

- **Stage 1 结构化**：简历原文 → JSON 画像。职责单一，prompt 短，跑偏概率低。
- **Stage 2 出题**：把「画像 + 题库检索结果」一起交给模型。此时模型拿到了锚点（题库里已有的 id 回填到 `bankQuestionId`），出题更贴近真实面试。

拆两步的好处：**画像可复用**（想重新出题不必重解析简历），而且每一步的输出都能单独排查。

### 异步任务链路

模型推理要几十秒，占着 Tomcat 工作线程是浪费，所以拆成「提交 + 轮询」：

```
POST /api/resume/analyze   → 校验配置、抽文本、写一条 PENDING 记录，立刻返回 {id, status}
                            ↓ 交给 resumeExecutor 线程池（core 2 / max 4 / queue 16）
后台线程                    → RUNNING → 调用模型 → SUCCESS 或 FAILED（失败原因写入 error_msg）
GET  /api/resume/{id}      → 前端轮询这个接口直到拿到终态
```

几个刻意的设计：

- **提交方法不加事务**：任务记录必须先真正提交，后台线程按 id 才查得到。
- **异步方法放在独立的 bean（`ResumeAnalysisJob`）**：`@Async` 依赖 Spring 代理，写在同一个类里自调用不会生效。
- **任务状态更新不加事务**：整个任务要跑几十秒，长事务会把 RUNNING 状态锁到结束，前端永远看不到中间态。
- **并发守卫**：同一用户同时只允许一个进行中的任务（按 `created_at` 限定 30 分钟窗口），避免连点两次花两份 token。
- **启动清理（`ResumeTaskRecovery`）**：进程被杀后残留的 PENDING/RUNNING 记录，启动时统一标记为 FAILED，否则前端会一直转圈、用户也永远提交不了新简历。
- **正文截断**：送进模型的简历正文上限 3 万字，避免个别 PDF 抽出几万字白白烧 token。

题库检索用的是画像里的技能名与项目技术栈做关键词模糊匹配（`title` / `tags`），命中不足 40 条。

### 错误处理

LLM 调用失败时会把原因写进 `resume_analysis.error_msg` 并在历史记录里标红，常见的几类都做了人话提示：

- `401` → API Key 无效或过期
- `404` → Base URL 少了 `/v1`，或模型名不存在
- `429` → 触发限流
- 超时 → 提示调大超时时间或换更快的模型
- 连接失败 → 提示确认 Base URL 与本机网络是否可达
- 返回非法 JSON → 自动尝试剥离 ` ```json ` 代码块和截取首尾大括号后重试解析

## 扩展题库

往 `backend/src/main/resources/seed/` 下加一个 JSON 文件（或往现有文件追加），重启后端即可**增量导入**——按 `title` 去重，不会重复插入：

```json
{
  "questions": [
    {
      "category": "java-concurrency",
      "title": "题目内容？",
      "answer": "参考答案，支持 **Markdown**。\n\n- 要点一\n- 要点二",
      "difficulty": 2,
      "tags": "标签1,标签2",
      "hot": 1
    }
  ]
}
```

`category` 必须用已有的分类 code（见 `01-java.json` 的 `categories` 段），否则该题会被跳过。新增分类时把 `categories` 段一起写进任意种子文件即可。

关闭自动导入：在 `application.yml` 里把 `app.seed.enabled` 设为 `false`。

### 从开源文档批量生成题目

题库里约九成题目（`04-javaguide.json`，1182 道）提取自 [JavaGuide](https://github.com/Snailclimb/JavaGuide)（Apache-2.0），
提取规则固化在 `scripts/import-javaguide.py` 里，可复现：

```bash
# 1) 只拉 Markdown 文档，不拉图片（约 20MB）
git clone --depth 1 --filter=blob:none --sparse https://github.com/Snailclimb/JavaGuide.git
cd JavaGuide && git sparse-checkout set docs && cd ..

# 2) 生成题库（先 --dry-run 看统计，确认后再正式写入）
python scripts/import-javaguide.py --docs ./JavaGuide/docs --dry-run
python scripts/import-javaguide.py --docs ./JavaGuide/docs

# 3) 结构门禁必须通过
python scripts/check_seed.py
```

这个管道解决的几个实际问题，也是它比"直接抓标题"复杂的地方：

| 问题 | 处理方式 |
|---|---|
| 文件名不可信 | `sql-questions-01.md` 名为 questions 实为 SQL 教程，靠**题感比**（该层级标题中"像问题"的占比）剔除，阈值 0.40 |
| 题集与文章要分开处理 | 题集文件（标题数 ≥8 且题感比 ≥0.40）放宽筛选保留名词型标题；知识点文档收紧，只留明确像问题的 |
| 过短标题没头没尾 | 「堆」「方法区」这类标题自动补上所属章节，变成「运行时数据区域：堆」 |
| 站内链接会 404 | 相对链接替换为纯文本，外部链接保留 |
| `answer` 是 TEXT 字段 | 超过 6000 字的答案在段落边界截断并注明是节选 |
| 同义重复 | 归一化精确匹配 + 相似度 ≥0.82 两级去重 |

> 内容来源、许可证与改动说明见 **[THIRD-PARTY-NOTICES.md](./THIRD-PARTY-NOTICES.md)** ——
> Apache-2.0 允许使用与修改，但必须保留声明，所以这份文件不是可选项。

## 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/register` | 注册，返回 JWT |
| POST | `/api/auth/login` | 登录 |
| GET | `/api/auth/me` | 当前用户 |
| PUT | `/api/auth/profile` | 更新资料（目标岗位/城市会喂给简历分析） |
| GET | `/api/categories` | 分类及各分类题数（一次 GROUP BY 取回） |
| GET | `/api/questions` | 分页检索（默认不返回答案） |
| GET | `/api/questions/{id}` | 题目详情（含答案） |
| GET | `/api/practice/today` | 今日题单，首次调用即时组卷（含新题/复习题标注与下次复习时间） |
| POST | `/api/practice/submit` | 提交作答（mastery：1 不会 / 2 模糊 / 3 掌握），返回下次复习间隔 |
| GET | `/api/practice/stats` | 仪表盘统计 |
| GET | `/api/practice/wrong-book` | 错题本 |
| POST | `/api/practice/append` | 把某题加入今日题单 |
| POST | `/api/questions/import` | 题库导入（JSON 数组，按「分类+标题」去重，分类不存在自动创建） |
| POST | `/api/jd/analyze` | 分析岗位 JD：抽关键词 → 题库召回 → 覆盖率 → 生成定向题单 |
| POST | `/api/interview/start` | 开始模拟面试，返回面试官开场问题 |
| POST | `/api/interview/answer` | 提交作答，返回面试官追问 + 打分 + 点评 |
| POST | `/api/interview/{id}/finish` | 结束面试并生成报告（幂等） |
| GET | `/api/interview/{id}` | 查询会话与全部轮次 |
| GET | `/api/interview/list` | 面试历史（带总评分） |
| POST | `/api/resume/analyze` | 上传简历，**立刻返回任务 id**（multipart） |
| GET | `/api/resume/{id}` | 查询分析状态与结果（前端轮询用） |
| GET | `/api/resume/list` | 历史分析记录 |
| DELETE | `/api/resume/{id}` | 删除分析记录 |
| GET/PUT | `/api/llm/setting` | 读取/保存模型配置（Key 读取只回掩码） |
| POST | `/api/llm/test` | 连通性自检 |

## 已知限制

- **只支持文字版 PDF**：扫描件需要先做 OCR，目前会明确提示「没能从这份文件里读到文字」。
- **题库检索用的是 `LIKE` 关键词匹配**，没有做向量检索，也没有相关性排序。题库规模上千后应换成 Elasticsearch 或加一层 embedding 召回；JD 召回复用的是同一套匹配。
- **SM-2 是简化版**：只用了 EF 与间隔两个量，没实现 FSRS 的「可提取性」建模与个性化参数拟合；自评是主观的，同一道题用户给「模糊」和「掌握」的尺度会漂移（模拟面试的打分可作为客观锚点补充）。
- **外键与级联未建**：表间只有逻辑关联，删除题目不会清理对应的刷题记录与 `review_state`。
- **限流是单机内存实现**（`ConcurrentHashMap` 滑动窗口）：多实例部署时需要换成 Redis 计数，否则每个实例各限各的。
- **`review_state` 只在作答时更新**：如果用户长时间不刷题，不会有后台任务去刷新 `next_review_at`，题单会在下次打开时一次性涌入大量到期题（当前按 `LIMIT` 截断，不会爆）。
