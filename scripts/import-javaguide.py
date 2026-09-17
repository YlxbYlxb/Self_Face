"""
把开源文档（当前适配 JavaGuide）转换成 SelfFace 的题库 seed 文件。

用途：题库内容来源可追溯、可复现。README 与 THIRD-PARTY-NOTICES.md 里承诺的
「提取规则、清洗规则、字段规则」都固化在这个脚本里，而不是靠人工一次性的加工。

用法：
    # 1) 先拿到文档（只拉 Markdown，不拉图片，约 20MB）
    git clone --depth 1 --filter=blob:none --sparse https://github.com/Snailclimb/JavaGuide.git
    cd JavaGuide && git sparse-checkout set docs && cd ..

    # 2) 生成题库（默认写入 backend/.../seed/04-javaguide.json）
    python scripts/import-javaguide.py --docs ./JavaGuide/docs

    # 3) 校验结构，必须通过
    python scripts/check_seed.py

设计要点
--------
* 判据是「标题密度 + 题感比」，不是文件名。JavaGuide 里有 `sql-questions-01.md`
  这种名叫 questions 实为教程的文件（题感比 0.02），必须靠内容特征排除。
* 题集文件与知识点文档用两套标题筛选策略：前者放宽（保留名词型标题），
  后者收紧（只留明确像问题的），否则知识点文档会灌进大量叙事片段。
* 与现有题库做两级去重：归一化精确匹配 + 相似度匹配，避免「同义不同字」重复。
* 答案要截断：question.answer 是 TEXT（utf8mb4 下约 2 万字上限），
  超长答案在段落边界截断并注明是节选。
"""
from __future__ import annotations

import argparse
import difflib
import json
import pathlib
import re
import sys
from collections import Counter

STAR = '\u2b50'
MAX_ANSWER = 6000
MAX_TITLE = 120
SIM_THRESHOLD = 0.82

SOURCE = {
    'name': 'JavaGuide',
    'url': 'https://github.com/Snailclimb/JavaGuide',
    'license': 'Apache-2.0',
    'note': ('本文件由 JavaGuide 文档提取、改写为问答结构（按标题切分、去除站内链接、'
             '统一字段、部分答案做了节选）。版权归原作者 Guide 所有，遵循 Apache-2.0 使用，'
             '在此注明出处。'),
}

# 路径前缀 -> 分类 code。只白名单，未列出的目录一律不取
CATEGORY_MAP = [
    ('java/basis/', 'java-basic'),
    ('java/collection/', 'java-collection'),
    ('java/concurrent/', 'java-concurrency'),
    ('java/jvm/', 'java-jvm'),
    ('java/io/', 'java-basic'),
    ('java/new-features/', 'java-basic'),
    ('database/mysql/', 'mysql'),
    ('database/sql/', 'mysql'),
    ('database/redis/', 'redis'),
    ('cs-basics/network/', 'network'),
    ('cs-basics/operating-system/', 'os'),
    ('cs-basics/algorithms/', 'algorithm'),
    ('cs-basics/data-structure/', 'algorithm'),
    ('system-design/framework/', 'spring'),
    ('distributed-system/', 'distributed'),
    ('high-availability/', 'distributed'),
    ('high-performance/message-queue/', 'mq'),
    ('high-performance/', 'distributed'),
    ('system-design/', 'project'),
    ('zhuanlan/', 'project'),
    ('ai/interview-questions/', 'ai'),
]

# 明确排除：技术随笔、个人经验、路线图、AI 编码实践等，不是题库内容
EXCLUDE_PREFIX = (
    'high-quality-technical-articles/', 'ai-coding/',
    'ai/agent/', 'ai/rag/', 'ai/llm-basis/', 'ai/system-design/',
    'ai/ai-core-concepts', 'roadmap/', 'books/', 'tools/', 'snippets/',
    'about-the-author/', 'javaguide/', 'open-source-project/',
    'interview-preparation/',
)

# 新分类（01~03 里没有的）
NEW_CATEGORIES = [
    {'code': 'distributed', 'name': '分布式与微服务',
     'description': '分布式理论、一致性、注册中心、网关与微服务治理', 'sortOrder': 12},
    {'code': 'mq', 'name': '消息队列与性能优化',
     'description': 'Kafka / RocketMQ / RabbitMQ 与高并发性能优化', 'sortOrder': 13},
    {'code': 'ai', 'name': 'AI 与大模型',
     'description': '大模型基础、RAG、Agent 与 AI 系统设计', 'sortOrder': 14},
]

CAT_NAME = {c['code']: c['name'] for c in NEW_CATEGORIES}
CAT_NAME.update({
    'java-basic': 'Java 基础', 'java-collection': '集合框架',
    'java-concurrency': '并发编程', 'java-jvm': 'JVM', 'spring': 'Spring 生态',
    'mysql': 'MySQL', 'redis': 'Redis', 'network': '计算机网络',
    'os': '操作系统', 'algorithm': '算法与数据结构', 'project': '项目与场景设计',
})

SKIP_TITLE = re.compile(
    r'^(参考|参考资料|参考链接|参考文献|本文目录|目录|前言|序言|导读|引子|背景|'
    r'小结|总结|写在最后|扩展阅读|推荐阅读|相关阅读|常见问题汇总|附录|更新记录|后记|'
    r'说明|写在前面|关于作者|本文档|欢迎|如何贡献|License|免责声明|版权声明|'
    r'知识点|面试题|面试准备|自我介绍|简历|项目经历|答题|答题技巧|其他|更多|'
    r'常用命令|常用工具|学习路线|如何学习|回顾|补充|注意)$'
)
SECTION_NOISE = re.compile(
    r'(示例|案例|代码演示|实践|实测|安装|配置步骤|如何安装|快速开始|使用教程|'
    r'命令一览|参数说明|API 列表|目录结构|项目结构|本文|上一节|下一节|'
    r'第[一二三四五六七八九十]+部分|^\d+[\.、])'
)
Q_PATTERNS = [
    (re.compile(r'[?？]\s*$'), 5),
    (re.compile(r'^(什么是|为什么|为何|如何|怎么|怎样|哪些|哪一种|哪个|是否|能否|'
                r'介绍|简述|说明|谈一谈|谈谈|讲一讲|讲讲|说一下|聊聊)'), 4),
    (re.compile(r'(区别|不同|对比|比较|优缺点|好处|坏处|作用|原理|机制|实现|'
                r'是什么|有哪些|为什么要|如何选择|怎么选|了解(吗|么)?|'
                r'会(不会|带来)|能否|可以吗|有什么)'), 3),
]

HEAD_RE = re.compile(r'^(#{1,6})\s+(.*?)\s*$')
IMG_RE = re.compile(r'!\[[^\]]*\]\([^)]*\)')
HTML_RE = re.compile(r'</?(div|p|br|img|a|table|tr|td|th|center|font|span|details|summary|h[1-6])[^>]*>', re.I)
BADGE_RE = re.compile(r'^\s*\[!\[[^\]]*\]\([^)]*\)\]\([^)]*\)\s*$')
COMMENT_RE = re.compile(r'<!--.*?-->', re.S)
LINK_RE = re.compile(r'\[([^\]]+)\]\((?!https?://)[^)]*\)')
TAIL_RE = re.compile(r'\n#{2,6}\s*(推荐阅读|参考|参考资料|参考文献|相关阅读)\s*$')


def strip_marks(t: str) -> str:
    """去掉标题里残留的装饰字符。

    原文大量使用 ⭐️ 标记重点，只删 U+2B50 会留下变体选择符 U+FE0F，
    在界面上表现为题干前面多一个看不见的字符（空格宽度错位）。
    """
    for ch in (STAR, '\ufe0f'):
        t = t.replace(ch, '')
    return t.strip()


def title_score(title: str) -> int:
    """给「这个标题像不像一道面试题」打分。"""
    s = 0
    for rx, w in Q_PATTERNS:
        if rx.search(title):
            s += w
    n = len(strip_marks(title))
    if n < 4:
        s -= 4
    elif n > 60:
        s -= 2
    if SECTION_NOISE.search(title):
        s -= 6
    return s


def norm_title(t: str) -> str:
    t = t.replace(STAR, '').replace('\ufe0f', '')
    t = re.sub(r'[\s\u3000]+', '', t)
    return re.sub(r'[?？。，,．\.：:；;！!、（）()""\'\'【】\[\]《》\-—_/\\]+', '', t.lower())


def clean_answer(text: str) -> str:
    text = COMMENT_RE.sub('', text)
    lines = []
    for ln in text.splitlines():
        if BADGE_RE.match(ln):
            continue
        s = ln.strip()
        if s.startswith('> ') and any(k in s for k in ('公众号', '扫码', '关注', '微信')):
            continue
        if s.startswith(':::'):
            continue
        lines.append(IMG_RE.sub('', HTML_RE.sub('', ln)))
    out = re.sub(r'\n{3,}', '\n\n', '\n'.join(lines)).strip()
    out = LINK_RE.sub(r'\1', out)          # 站内相对链接 -> 纯文本，避免 404
    m = TAIL_RE.search(out)                # 去掉结尾的「推荐阅读」尾巴
    if m:
        out = out[:m.start()].strip()
    if len(out) > MAX_ANSWER:
        cut = out.rfind('\n\n', 0, MAX_ANSWER)
        if cut < MAX_ANSWER * 0.6:
            cut = MAX_ANSWER
        out = out[:cut].rstrip() + '\n\n> （原文较长，此处为节选，完整内容见 JavaGuide 原文。）'
    return out


def parse_file(path: pathlib.Path, docs: pathlib.Path):
    raw = path.read_text(encoding='utf-8', errors='replace')
    lines = raw.splitlines()
    heads = []
    for i, ln in enumerate(lines):
        m = HEAD_RE.match(ln)
        if m:
            heads.append((i, len(m.group(1)), m.group(2)))

    def q_ratio(lvl):
        ts = [h[2] for h in heads if h[1] == lvl]
        if not ts:
            return 0.0, 0
        return sum(1 for t in ts if title_score(t) >= 3) / len(ts), len(ts)

    r3, n3 = q_ratio(3)
    r2, n2 = q_ratio(2)
    if n3 >= 8 and r3 >= 0.40:
        levels, tier = [3], 'A'        # 题集文件
    elif n2 >= 8 and r2 >= 0.40:
        levels, tier = [2], 'A'
    else:
        levels, tier = [2, 3], 'B'     # 知识点文档

    rel = str(path.relative_to(docs)).replace('\\', '/')
    items = []
    for li, lvl, title in heads:
        if lvl not in levels:
            continue
        tt = strip_marks(title)
        if not tt or SKIP_TITLE.match(tt):
            continue
        sc = title_score(title)
        if tier == 'B' and sc < 3:
            continue
        if tier == 'A' and sc < -3:
            continue

        parent = ''
        for (pi, plvl, ptitle) in heads:
            if pi < li and plvl < lvl:
                parent = strip_marks(ptitle)
        if len(tt) <= 3:
            # 「堆」「方法区」这类过短标题补上章节名，否则题目没头没尾
            if parent and 2 < len(parent) <= 20 and not SKIP_TITLE.match(parent):
                tt = '%s：%s' % (parent, tt)
            else:
                continue

        end = len(lines)
        for (ni, nlvl, _) in heads:
            if ni > li and nlvl <= lvl:
                end = ni
                break
        body = clean_answer('\n'.join(lines[li + 1:end]))
        if len(body) < 80 or len(re.sub(r'[\s`*#>|\-]', '', body)) < 40:
            continue

        cat = None
        for prefix, code in CATEGORY_MAP:
            if rel.startswith(prefix):
                cat = code
                break
        if cat is None:
            continue

        items.append({'category': cat, 'title': tt, 'answer': body,
                      'source_file': rel, 'parent': parent,
                      'tier': tier, 'score': sc})
    return items


def load_existing(seed_dir: pathlib.Path):
    titles = []
    for p in sorted(seed_dir.glob('*.json')):
        if p.name.startswith('04-'):
            continue                    # 生成的文件不参与去重基准
        try:
            data = json.loads(p.read_text(encoding='utf-8'))
        except Exception:
            continue
        for q in data.get('questions', []):
            t = (q.get('title') or '').strip()
            if t:
                titles.append(t)
    return titles


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--docs', required=True, help='JavaGuide 的 docs 目录')
    ap.add_argument('--out', default=None, help='输出 seed 文件路径')
    ap.add_argument('--dry-run', action='store_true', help='只统计不写文件')
    args = ap.parse_args()

    docs = pathlib.Path(args.docs).resolve()
    if not docs.is_dir():
        print('[FAIL] 找不到文档目录：%s' % docs)
        return 1

    root = pathlib.Path(__file__).resolve().parent.parent
    seed_dir = root / 'backend' / 'src' / 'main' / 'resources' / 'seed'
    out = pathlib.Path(args.out) if args.out else seed_dir / '04-javaguide.json'

    files = [p for p in sorted(docs.rglob('*.md'))
             if '/.vuepress/' not in str(p).replace('\\', '/')
             and p.name != 'README.md']
    raw = []
    for f in files:
        rel = str(f.relative_to(docs)).replace('\\', '/')
        if any(rel.startswith(x) for x in EXCLUDE_PREFIX):
            continue
        raw.extend(parse_file(f, docs))

    # 批内去重：标题归一后保留分数高、答案更完整的一份
    best: dict[str, dict] = {}
    for it in raw:
        k = norm_title(it['title'])
        if not k:
            continue
        cur = best.get(k)
        if cur is None:
            best[k] = it
        elif (it['tier'] == 'A', it['score'], len(it['answer'])) > \
             (cur['tier'] == 'A', cur['score'], len(cur['answer'])):
            best[k] = it
    uniq = list(best.values())

    existing = load_existing(seed_dir)
    existing_norm = [norm_title(t) for t in existing]

    kept, dropped = [], Counter()
    seen = set()
    for it in uniq:
        title = it['title'].strip()
        answer = it['answer']
        key = norm_title(title)
        if not key or len(title) > MAX_TITLE:
            dropped['题干异常'] += 1
            continue
        if key in seen:
            dropped['批内重复'] += 1
            continue
        if key in existing_norm:
            dropped['与现有题库重复'] += 1
            continue
        if difflib.get_close_matches(key, existing_norm, n=1, cutoff=SIM_THRESHOLD):
            dropped['与现有题库近似'] += 1
            continue
        # 包含式重复：相似度算不出「一个是另一个的前缀」这种，
        # 例如导入的「虚拟内存」与现有的「虚拟内存是什么？为什么需要它？」其实是同一道题
        if any(len(o) >= 8 and (o in key or key in o) for o in existing_norm):
            dropped['与现有题库包含'] += 1
            continue
        seen.add(key)

        tags = []
        if it.get('parent'):
            tags.append(it['parent'])
        tags.append(CAT_NAME.get(it['category'], it['category']))
        n = len(answer)
        kept.append({
            'category': it['category'],
            'title': title,
            'difficulty': 1 if n < 300 else (2 if n < 1200 else 3),
            'hot': 1 if (it['tier'] == 'A' and it['score'] >= 5) else 0,
            'tags': ','.join(dict.fromkeys(t for t in tags if t))[:200],
            'answer': answer,
        })

    used = {q['category'] for q in kept}
    doc = {'_source': SOURCE,
           'categories': [c for c in NEW_CATEGORIES if c['code'] in used],
           'questions': kept}

    print('扫描 %d 个文档、原始候选 %d、批内去重后 %d' % (len(files), len(raw), len(uniq)))
    print('可导入 %d 道（丢弃：%s）' % (len(kept), dict(dropped)))
    print('分布:', dict(Counter(q['category'] for q in kept).most_common()))
    print('导入后总题数: %d' % (len(existing) + len(kept)))

    if args.dry_run:
        print('[dry-run] 未写入文件')
        return 0

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding='utf-8')
    print('已写入 %s (%.1f MB)' % (out, out.stat().st_size / 1048576))
    return 0


if __name__ == '__main__':
    sys.exit(main())
