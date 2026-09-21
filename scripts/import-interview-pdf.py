"""
把面试题 PDF 讲义转成 SelfFace 题库 seed 文件。

背景
----
题库需要持续增量维护，所以提取规则必须固化在脚本里，而不是靠人工一次性搬运。
本脚本与 import-javaguide.py 是同一套思路的两个数据源适配：

    源            结构            提取依据
    JavaGuide     Markdown 标题   '#' 层级 + 题感比判定题集文件
    PDF 讲义      PDF 版面       字号层级（科目 / 章节 / 题目）+ 行间距

用法
----
    python scripts/import-interview-pdf.py                        # 默认读 pdf-inbox/
    python scripts/import-interview-pdf.py --src "D:\\path\\to\\pdf"
    python scripts/import-interview-pdf.py --only java            # 只跑一份，调试用
    python scripts/import-interview-pdf.py --dry-run              # 只统计不写文件
    python scripts/check_seed.py                                  # 必须通过

PDF 文件名不入代码：BANKS 里用的是通配匹配，换版本、换前缀都不影响。
把 PDF 放进 `pdf-inbox/`（已 gitignore），或用 `--src` 指定目录。

这个数据源特有的三个坑（都已在代码里处理，改动前请先读）
--------------------------------------------------------
1. **伪汉字**：PDF 字体子集把一批汉字编码到了「康熙部首区」(U+2E80-U+2FDF)，
   例如 `⼩` 实为 `小`、`⾯试题` 实为 `面试题`。用 NFKC 逐字归回常规汉字。

2. **错字**：同一字体子集把「口」错映射成了「又」，`接口` 变成 `接又`、
   `哑口无言` 变成 `哑又无言`。这不是编码问题，NFKC 修不了，只能按词表替换。
   又因为排版会把词拆到两行（`接` 行尾 + `又` 行首），替换必须允许中间夹一个换行，
   且在**段落合并之后**执行——否则会漏掉跨行的那些。

3. **代码空格丢失**：`get_text("dict")` 的 span.text 不含排版空格，
   `public static void main` 会变成 `publicstaticvoidmain`。
   等宽字体的行必须用 span 之间的 bbox 间隙反推空格数量。
"""
from __future__ import annotations

import argparse
import difflib
import json
import pathlib
import re
import sys
import unicodedata
from collections import Counter

import fitz

# ----------------------------------------------------------------------------
# 常量
# ----------------------------------------------------------------------------
MAX_ANSWER = 6000           # question.answer 是 TEXT，留足余量
MAX_TITLE = 300
SIM_THRESHOLD = 0.82
MONO_SPACE = 5.58           # Menlo 9.3pt 的单字符宽度（由 bbox 实测）
MONO_FONTS = ("Menlo", "Mono", "Courier", "Consol")
MIN_LINE_SIZE = 8.8         # 小于此字号视为页眉页脚/图注
GAP_SAME_PARA = 3.5         # 行间距小于此值视为同段续行（实测：段内 ~1.4，段间 ~5.8）

# 字号分层（由各 PDF 实测归纳，跨文件通用）
TOP_MIN = 22.0              # 顶层：科目 / 面试场次
MID_MIN = 17.5              # 中层：章节 / 知识点（也可能直接是题目）
TOPIC_MIN = 15.0            # 题目标题

SOURCE = {
    "name": "面试题 PDF 讲义合集",
    "license": "版权归原作者所有，请勿转载或再分发。",
    "note": ("本文件由本地 PDF 讲义提取、改写为问答结构"
             "（按字体层级切分题目、去除站内链接与推广章节、统一字段、部分答案做节选）。"
             "仅供个人本地学习使用，未经授权不得转载或再分发。"),
}

# 字体子集把「口」错映射成「又」：按词替换。顺序无所谓，但必须逐字精确。
TYPO_FIX = [
    ("接又", "接口"), ("入又", "入口"), ("出又", "出口"), ("端又", "端口"),
    ("窗又", "窗口"), ("门又", "门口"), ("开又", "开口"), ("网又", "网口"),
    ("哑又", "哑口"), ("又罩", "口罩"), ("又袋", "口袋"),
]

# 名称 -> 分类 code。名字来自 PDF 里的科目标题与大厂面经的知识点小标题。
CAT_MAP = {
    # 题库型 PDF 顶层科目
    "Java基础面试题": "java-basic",
    "Java集合面试题": "java-collection",
    "Java并发编程面试题": "java-concurrency",
    "Java虚拟机面试题": "java-jvm",
    "Spring面试题": "spring",
    "MySQL面试题": "mysql",
    "Redis面试题": "redis",
    "计算机网络面试题": "network",
    "操作系统面试题": "os",
    "数据结构与算法面试题": "algorithm",
    "消息队列面试题": "mq",
    "分布式面试题": "distributed",
    "系统设计面试题": "project",
    "Linux命令面试题": "linux",
    "Git面试题": "linux",
    "C++面试题": "cpp",
    "Golang面试题": "golang",
    # 测试开发系列的四个科目
    "测试开发面试题全攻略": "test-dev",
    "业务测试面试题": "test-dev",
    "Python自动化测试面试题": "test-dev",
    "Java自动化测试面试题": "test-dev",
    "性能测试面试题": "test-dev",
    # 大厂面经的知识点小标题
    "计算机网络": "network",
    "网络": "network",
    "密码学和计网": "network",
    "操作系统": "os",
    "算法": "algorithm",
    "算法题": "algorithm",
    "数据结构": "algorithm",
    "MySQL": "mysql",
    "数据库": "mysql",
    "Redis": "redis",
    "消息队列": "mq",
    "分布式": "distributed",
    "系统设计": "project",
    "场景题": "project",
    "Java": "java-basic",
    "Java基础": "java-basic",
    "Java集合": "java-collection",
    "Java并发": "java-concurrency",
    "java并发": "java-concurrency",
    "Java框架": "spring",
    "JVM": "java-jvm",
    "Spring": "spring",
    "C++": "cpp",
    "c++": "cpp",
    "Golang": "golang",
    "Go": "golang",
    "语言": "golang",
}

# 兜底：题干关键词 -> 分类。用于大厂面经里那些没有知识点小标题的场次，
# 例如「MySQL 常见的存储引擎是哪个」。顺序有意义——越具体的写在越前面。
TITLE_CAT_HINTS = [
    (re.compile(r"mysql|innodb|myisam|binlog|慢查询|分库分表|隔离级别|可重复读|读提交|"
                r"mvcc|索引|外键|varchar|sql|事务|主键|范式|explain", re.I), "mysql"),
    (re.compile(r"redis|雪崩|穿透|击穿|\brdb\b|aof|哨兵|缓存|集群", re.I), "redis"),
    (re.compile(r"kafka|rocketmq|rabbitmq|消息队列|消息堆压|消息丢失|消息中间件", re.I), "mq"),
    (re.compile(r"tcp|udp|http|https|dns|websocket|握手|拥塞|滑动窗|流量控制|epoll|"
                r"select|poll|零拷|网络|arp|分块传输|url|"
                r"(?<![A-Za-z])bio(?![A-Za-z])|(?<![A-Za-z])nio(?![A-Za-z])|"
                r"(?<![A-Za-z])aio(?![A-Za-z])|io模型|非阻塞", re.I), "network"),
    (re.compile(r"jvm|垃圾回收|类加载|字节码|栈帧|新生代|老年代|\bcms\b|\bg1\b", re.I), "java-jvm"),
    (re.compile(r"spring|bean|ioc|aop|事务传播|循环依赖", re.I), "spring"),
    (re.compile(r"线程池|并发|synchronized|syncronized|volatile|lock|cas|aqs|守护线程|线程", re.I),
     "java-concurrency"),
    (re.compile(r"进程|虚拟内存|页表|pagecache|中断|内核态|操作系统|调度|内存布局|"
                r"堆空间|栈空间|系统调用|信号量|死锁", re.I), "os"),
    (re.compile(r"分布式|一致性|raft|paxos|限流|负载均衡|微服务|(?<![A-Za-z])qps(?![A-Za-z])",
                re.I), "distributed"),
    (re.compile(r"算法|排序|二叉树|链表|动态规划|哈希表|复杂度|红黑树|b\+树", re.I), "algorithm"),
    (re.compile(r"goroutine|golang|(?<![A-Za-z])go(?![A-Za-z])|channel|gmp|slice", re.I), "golang"),
    (re.compile(r"c\+\+|stl|智能指针|虚函数|拷贝构造", re.I), "cpp"),
    (re.compile(r"docker|k8s|kubernetes|linux|shell|(?<![A-Za-z])git(?![A-Za-z])|命令行|kubectl",
                re.I), "linux"),
    (re.compile(r"秒杀|短链|点赞|发号器|抢购|订单|系统设计", re.I), "project"),
    (re.compile(r"集合|hashmap|arraylist|linkedlist|concurrenthashmap|"
                r"(?<![A-Za-z])list(?![A-Za-z])|(?<![A-Za-z])set(?![A-Za-z])", re.I), "java-collection"),
    (re.compile(r"设计模式|观察者模式|单例|工厂模式|(?<![A-Za-z])static(?![A-Za-z])|"
                r"面向对象|面向过程", re.I), "java-basic"),
    (re.compile(r"java|jdk|反射|注解|序列化|泛型|异常|(?<![A-Za-z])string(?![A-Za-z])",
                re.I), "java-basic"),
    (re.compile(r"测试开发|自动化测试|测试用例|性能测试", re.I), "test-dev"),
]

# 这些「看起来像标题、其实不是题目」的段落，直接跳过
SKIP_TITLE = re.compile(
    r"^(推荐学习|推荐阅读|参考资料|参考|小结|总结|写在最后|最后说几句|读者总结|"
    r"反问|感觉|感受|不足之处|问题记录|面试记录|面试总结|场景题|其他|"
    r"自我介绍|简历|项目|项目问题|项目介绍|智力题|"
    r"本文|目录|前言|关于|番外|广告|"
    r".*(训练营|私教|课程|报名|公众号|扫码)|"
    r".{0,12}(面试篇|面试题|面试题全攻略|面试题汇总)$)"
)

# 题干前导序号：「1. 」/「1.1 」/「三、」/「（2）」。lookahead 排除「1.5 倍」这类数字。
NUMBER_PREFIX = re.compile(
    r"^\s*[（(\[]?\s*(?:"
    r"\d{1,2}(?:[.．]\d{1,2})+\s+"                    # 1.1 / 2.3.4（多级序号，自带空格）
    r"|\d{1,2}\s*[）)\].、,，:：]\s*(?=[^\d])"          # 1. / 1) / 1、
    r"|[一二三四五六七八九十]{1,2}\s*[、.，,：:]\s*"      # 一、 / 二.
    r")")

# 答案开头冗余的标签（答案本身就是参考答案，重复一次没意义）
ANSWER_LABEL = re.compile(r"^(?:参考答案|参考解答|答案|解析|解答)\s*[:：]\s*")

# 行尾是否「已经写完」（有句末标点）——用于判断跨页续行
TAIL_CLOSED = re.compile(r"[。！？!?；;：:）)\]”\"]$")

# 页码 / 纯符号行
NOISE_LINE = re.compile(r"^[\s\d\-—·.,、。|]+$")

NEW_CATEGORIES = [
    {"code": "linux", "name": "Linux 与工具命令",
     "description": "Linux 常用命令、性能排查、进程与网络诊断、Git 命令", "sortOrder": 15},
    {"code": "cpp", "name": "C++",
     "description": "C++ 基础、面向对象、STL、智能指针、内存管理与新特性", "sortOrder": 16},
    {"code": "golang", "name": "Golang",
     "description": "Go 基础、Slice/Map/Channel、GMP 调度、内存管理与垃圾回收", "sortOrder": 17},
    {"code": "test-dev", "name": "测试开发",
     "description": "测试基础理论、自动化测试、性能测试与测试开发实践", "sortOrder": 18},
]

CAT_NAME = {c["code"]: c["name"] for c in NEW_CATEGORIES}
CAT_NAME.update({
    "java-basic": "Java 基础", "java-collection": "集合框架",
    "java-concurrency": "并发编程", "java-jvm": "JVM", "spring": "Spring 生态",
    "mysql": "MySQL", "redis": "Redis", "network": "计算机网络",
    "os": "操作系统", "algorithm": "算法与数据结构",
    "mq": "消息队列与性能优化", "distributed": "分布式与微服务",
    "project": "项目与场景设计",
})

# ----------------------------------------------------------------------------
# 数据源配置：每份 PDF 抽哪些科目、以及大厂面经的场次是否算题目来源
#
# 用通配匹配而不是写死文件名：文件名里通常带着来源标识与版本号，
# 写死既会把来源信息带进代码、又会在换个版本后直接失效。
# ----------------------------------------------------------------------------
BANKS = [
    {"key": "java", "glob": "300道*Java面试题*.pdf"},
    {"key": "mysqlredis", "glob": "150道*MySQL+Redis*.pdf"},
    {"key": "netos", "glob": "150道*计算机网络*操作系统*.pdf"},
    {"key": "mqds", "glob": "50道*消息队列*分布式*.pdf"},
    {"key": "linuxgit", "glob": "30道*Linux*Git*.pdf"},
    {"key": "testdev", "glob": "350道*测试开发*.pdf"},
    {"key": "cpp", "glob": "100道*CPP*.pdf"},
    {"key": "golang", "glob": "100道*Golang*.pdf"},
    {"key": "exp", "glob": "大厂*面试真题*白色*.pdf"},
]

QUESTION_PAT = re.compile(
    r"[?？]\s*$|^(什么是|为什么|为何|如何|怎么|怎样|哪些|哪一种|哪个|是否|能否|"
    r"介绍|简述|说明|谈一谈|谈谈|讲一讲|讲讲|说一下|聊聊|聊聊|知道|了解)"
)
QUESTION_IN = re.compile(
    r"(区别|不同|对比|比较|优缺点|好处|坏处|作用|原理|机制|实现|是什么|有哪些|"
    r"为什么要|如何|怎么|为什么|了解|场景|流程|过程|设计)"
)

DEBUG = False


# ----------------------------------------------------------------------------
# 文本清洗
# ----------------------------------------------------------------------------
# CJK 部首补充区（U+2E80-U+2EFF）里 NFKC 没有映射的伪汉字。
# 这一区的字符字形与汉字极像但不是汉字，如 U+2ED3「⻓」≠「长」，
# 界面上会显示成异体字且搜索不到，必须手工对应。
# 这份表由「扫描全部 PDF + 逐个看上下文」得出，共 13 个、647 次。
RADICAL_FIX = {
    "\u2ec4": "西", "\u2ec5": "见", "\u2ec6": "角", "\u2ec9": "贝",
    "\u2ecb": "车", "\u2ed3": "长", "\u2ed4": "门", "\u2eda": "页",
    "\u2edb": "风", "\u2edc": "飞", "\u2ee2": "马", "\u2ee6": "鸟",
    "\u2eec": "齐",
}


def nfkc(s: str, stats: Counter | None = None) -> str:
    """把伪汉字归一回常规汉字。

    两个来源：
    * 康熙部首区（U+2F00-U+2FD5）有 NFKC 映射，直接归一；
    * CJK 部首补充区（U+2E80-U+2EFF）多数没有映射，查 RADICAL_FIX。
    未在表里的部首字符会被记入 stats 并在结束时告警——出现新的伪汉字时能立刻发现。
    """
    out = []
    for ch in s:
        o = ord(ch)
        if not (0x2E80 <= o <= 0x2FDF):
            out.append(ch)
            continue
        norm = unicodedata.normalize("NFKC", ch)
        if norm != ch:
            out.append(norm)
            continue
        mapped = RADICAL_FIX.get(ch)
        if mapped:
            out.append(mapped)
        else:
            out.append(ch)
            if stats is not None:
                stats["未映射部首字符:%s" % ("U+%04X" % o)] += 1
    return "".join(out)


def fix_typo(s: str) -> str:
    """修正「口→又」错映射。允许词中间夹一个换行（排版断行）。"""
    for a, b in TYPO_FIX:
        pat = re.escape(a[0]) + r"[ \t]*\n?[ \t]*" + re.escape(a[1])
        s = re.sub(pat, b, s)
    return s


def clean_text(s: str, stats: Counter | None = None) -> str:
    s = nfkc(s, stats)
    s = fix_typo(s)
    s = re.sub(r"[ \t]+", " ", s)
    return s.strip()


def norm_title(t: str) -> str:
    t = nfkc(t)
    t = re.sub(r"[\s\u3000]+", "", t)
    return re.sub(r"[?？。，,．\.：:；;！!、（）()“”‘’【】\[\]《》\-—_/\\|]+", "", t.lower())


# ----------------------------------------------------------------------------
# PDF 行提取
# ----------------------------------------------------------------------------
def rebuild_mono(ln: dict, base_x: float) -> str:
    """等宽字体行：用 bbox 间隙反推空格，恢复缩进与词间距。"""
    out = []
    prev_x1 = None
    for sp in ln["spans"]:
        txt = sp["text"]
        if not txt:
            continue
        x0, x1 = sp["bbox"][0], sp["bbox"][2]
        if prev_x1 is None:
            gap = x0 - base_x
            if gap > 2.0:
                out.append(" " * max(1, int(round(gap / MONO_SPACE))))
        else:
            gap = x0 - prev_x1
            if gap > 1.5:
                out.append(" " * max(1, int(round(gap / MONO_SPACE))))
        out.append(txt)
        prev_x1 = x1
    return "".join(out)


def read_rows(path: pathlib.Path, stats: Counter) -> list[dict]:
    """读出所有有效行，按阅读顺序排好。"""
    doc = fitz.open(path)
    rows: list[dict] = []
    for pno in range(doc.page_count):
        page = doc[pno]
        page_rows = []
        for blk in page.get_text("dict")["blocks"]:
            if blk.get("type") != 0:            # 图片块跳过
                continue
            for ln in blk.get("lines", []):
                spans = [s for s in ln["spans"] if s["text"].strip()]
                if not spans:
                    continue
                size = max(round(s["size"], 1) for s in spans)
                if size < MIN_LINE_SIZE:
                    continue
                x0, y0, x1, y1 = ln["bbox"]
                # 代码行判定用「等宽字符占比」而不是「全部 span 都是等宽字体」：
                # 代码里的中文注释由宋体渲染，若要求全等宽会把带注释的代码行误判成正文，
                # 结果把代码块切成好几段。
                mono_chars = sum(len(s["text"]) for s in spans
                                 if any(k in s["font"] for k in MONO_FONTS))
                total_chars = sum(len(s["text"]) for s in spans)
                mono = total_chars > 0 and mono_chars / total_chars >= 0.5
                page_rows.append({
                    "page": pno + 1, "y0": y0, "y1": y1, "x0": x0, "x1": x1,
                    "size": size, "mono": mono, "spans": spans,
                })
        # 等宽行的缩进基准：同页等宽行的最小左边界
        mono_x = [r["x0"] for r in page_rows if r["mono"]]
        base_x = min(mono_x) if mono_x else 0.0
        for r in page_rows:
            r["text"] = clean_text(rebuild_mono(r, base_x) if r["mono"]
                                   else "".join(s["text"] for s in r["spans"]), stats)
            r["base_x"] = base_x
        rows.extend(r for r in page_rows if r["text"] and not NOISE_LINE.match(r["text"]))
    return rows


# ----------------------------------------------------------------------------
# 结构解析：切出题目与答案
# ----------------------------------------------------------------------------
def is_question_like(t: str) -> bool:
    return bool(QUESTION_PAT.search(t) or QUESTION_IN.search(t))


def build_blocks(rows: list[dict]) -> list[dict]:
    """把标题行合并成「逻辑标题」，同一道题被排版断成多行时并成一块。

    顺序很关键：**必须先合并、再判断角色**。
    否则「怎么判断内存是否在替换？」+「的频率？」这种同一题的两行
    （字号都是题目标题级、间距极小）会被看成两个独立标题、互相当成分组，
    最后切成两道残题。
    """
    blocks: list[dict] = []
    for i, r in enumerate(rows):
        if r["size"] < TOPIC_MIN:
            continue
        if blocks:
            b = blocks[-1]
            prev = rows[b["end"] - 1]
            if b["end"] == i and _is_continuation(prev, r):
                b["end"] = i + 1
                b["parts"].append(r["text"])
                continue
        blocks.append({"start": i, "end": i + 1, "size": r["size"],
                       "parts": [r["text"]], "text": ""})

    for b in blocks:
        title = re.sub(r"\s{2,}", " ", fix_typo("".join(b["parts"])).strip())
        b["text"] = NUMBER_PREFIX.sub("", title).strip()
    return blocks


def _is_continuation(prev: dict, cur: dict) -> bool:
    """cur 是不是 prev 那个标题被排版断下来的后半截。

    实测：真正的续行间距是 1.8，而相邻的独立标题最少也有 105，
    所以「字号同档 + 间距 ≤ 10」足够把两者分开。
    注意字号只要求同档（±0.6）而不是「落在题目标题档」——
    Linux、系统设计那几份的题目本身就是 18.5，与章节同档。
    """
    if abs(cur["size"] - prev["size"]) > 0.6:
        return False                                  # 不同层级，不可能是一句话
    if cur["page"] == prev["page"]:
        return cur["y0"] - prev["y1"] <= 10
    return not TAIL_CLOSED.search(prev["text"])        # 跨页：上一行明显没写完


def assign_roles(blocks: list[dict]) -> None:
    """给每个逻辑标题定角色：top / mid / topic / mid_orphan。

    中层字号（17.5~22）在不同 PDF 里含义不同——题库型里是章节，
    Linux、系统设计那几份里直接就是题目。判据：它后面还有没有更小的标题。
    """
    for pos, b in enumerate(blocks):
        if b["size"] >= TOP_MIN:
            b["role"] = "top"
        elif b["size"] >= MID_MIN:
            nxt = blocks[pos + 1] if pos + 1 < len(blocks) else None
            if nxt is not None and TOPIC_MIN <= nxt["size"] < MID_MIN:
                b["role"] = "mid"                     # 后面还有题目 → 它是分组
            elif is_question_like(b["text"]):
                b["role"] = "topic"                   # 直接带答案 → 它本身是题目
            else:
                b["role"] = "mid_orphan"              # 空章节
        else:
            b["role"] = "topic"


def parse_pdf(path: pathlib.Path, bank_key: str, stats: Counter) -> list[dict]:
    rows = read_rows(path, stats)
    if not rows:
        return []

    blocks = build_blocks(rows)
    assign_roles(blocks)

    block_at: dict[int, dict] = {}
    for b in blocks:
        for k in range(b["start"], b["end"]):
            block_at[k] = b

    items: list[dict] = []
    ctx = {"top": "", "mid": ""}
    cur: dict | None = None

    def flush():
        nonlocal cur
        if cur is not None:
            body = render_body(cur["answer_rows"])
            if len(body) >= 60 and len(re.sub(r"[\s`*#>|\-]", "", body)) >= 30:
                cur["answer"] = body
                cur.pop("answer_rows")
                items.append(cur)
            else:
                stats["答案过短丢弃"] += 1
        cur = None

    for i, r in enumerate(rows):
        b = block_at.get(i)
        if b is not None:
            if i != b["start"]:
                continue                              # 已并入题干的续行
            role = b["role"]
            if role == "top":
                flush()
                ctx = {"top": b["text"], "mid": ""}
                continue
            if role in ("mid", "mid_orphan"):
                flush()
                ctx["mid"] = b["text"]
                continue

            flush()                                   # role == "topic"
            title = b["text"]
            if not title:
                continue
            if SKIP_TITLE.match(title) or NOISE_LINE.match(title):
                stats["非题目标题跳过"] += 1
                continue
            if len(title) > MAX_TITLE:
                stats["题干过长丢弃"] += 1
                continue
            cat = resolve_category(ctx["top"], ctx["mid"], title)
            if cat is None:
                stats["无法归类丢弃"] += 1
                if DEBUG:
                    print("   [无归类] top=%r mid=%r title=%r"
                          % (ctx["top"][:26], ctx["mid"][:26], title[:40]))
                continue
            cur = {
                "category": cat,
                "title": title,
                "source_top": ctx["top"],
                "source_mid": ctx["mid"],
                "source_key": bank_key,
                "answer_rows": [],
            }
            continue
        if cur is not None:
            cur["answer_rows"].append(r)

    flush()
    return items


_CAT_INDEX: dict[str, str] | None = None


def _norm_key(s: str) -> str:
    return re.sub(r"[\s\u3000]+", "", nfkc(s)).lower()


def _lookup(name: str) -> str | None:
    global _CAT_INDEX
    if not name:
        return None
    if _CAT_INDEX is None:
        _CAT_INDEX = {_norm_key(k): v for k, v in CAT_MAP.items()}
    return _CAT_INDEX.get(_norm_key(name))


def resolve_category(top: str, mid: str, title: str) -> str | None:
    """四级回退确定分类，越靠前越可信：

    1. 二级知识点（大厂面经里的「Redis」「MySQL」小标题）
    2. 顶层科目（题库 PDF 里的「MySQL面试题」）
    3. 题干前缀（面经里常见的「MySQL-MVCC」「操作系统-死锁怎么产生的」）
    4. 题干关键词推断（面经里既没有小标题也没有前缀的场次）

    前三级都是「PDF 自己声明过的分类」，第四级是推断——只为不丢题才用。
    """
    for name in (mid, top):
        hit = _lookup(name)
        if hit:
            return hit

    m = re.match(r"^([\u4e00-\u9fffA-Za-z+#]{2,10})\s*[-—－‐:：]\s*\S", title)
    if m:
        hit = _lookup(m.group(1))
        if hit:
            return hit

    for pat, code in TITLE_CAT_HINTS:
        if pat.search(title):
            return code
    return None


def render_body(ans_rows: list[dict]) -> str:
    """把答案行渲染成 Markdown：正文按间距合并成段，等宽行包成代码块。"""
    out: list[str] = []
    buf: list[str] = []
    code: list[str] = []
    prev = None

    def flush_para():
        if buf:
            txt = fix_typo("".join(buf)).strip()
            if txt:
                out.append(txt)
            buf.clear()

    def flush_code():
        if code:
            body = fix_typo("\n".join(code)).rstrip()
            if body.strip():
                out.append("```\n" + body + "\n```")
            code.clear()

    for r in ans_rows:
        if r["mono"]:
            flush_para()
            if prev is not None and not prev["mono"] and code:
                flush_code()
            code.append(r["text"])
        else:
            if code:
                flush_code()
            if prev is None or prev["mono"]:
                flush_para()
                buf.append(r["text"])
            else:
                same_page = prev["page"] == r["page"]
                gap = r["y0"] - prev["y1"] if same_page else 999
                if gap <= GAP_SAME_PARA:
                    buf.append(r["text"])
                else:
                    flush_para()
                    buf.append(r["text"])
        prev = r

    flush_para()
    flush_code()

    text = "\n\n".join(out)
    text = ANSWER_LABEL.sub("", text.strip())
    text = re.sub(r"\n{3,}", "\n\n", text).strip()
    if len(text) > MAX_ANSWER:
        cut = text.rfind("\n\n", 0, MAX_ANSWER)
        if cut < MAX_ANSWER * 0.6:
            cut = MAX_ANSWER
        text = text[:cut].rstrip() + "\n\n> （原文较长，此处为节选。）"
    return text


# ----------------------------------------------------------------------------
# 去重
# ----------------------------------------------------------------------------
def load_existing(seed_dir: pathlib.Path, exclude: str):
    titles = []
    for p in sorted(seed_dir.glob("*.json")):
        if p.name == exclude:
            continue
        try:
            data = json.loads(p.read_text(encoding="utf-8"))
        except Exception:
            continue
        for q in data.get("questions", []):
            t = (q.get("title") or "").strip()
            if t:
                titles.append(t)
    return titles


def dedup(items: list[dict], existing: list[str], stats: Counter) -> list[dict]:
    existing_norm = [norm_title(t) for t in existing]
    kept, seen = [], set()
    for it in items:
        key = norm_title(it["title"])
        if not key or len(key) < 3:
            stats["题干异常丢弃"] += 1
            continue
        if key in seen:
            stats["批内重复"] += 1
            continue
        if key in existing_norm:
            stats["与现有题库重复"] += 1
            continue
        if difflib.get_close_matches(key, existing_norm, n=1, cutoff=SIM_THRESHOLD):
            stats["与现有题库近似"] += 1
            continue
        if any(len(o) >= 8 and (o in key or key in o) for o in existing_norm):
            stats["与现有题库包含"] += 1
            continue
        seen.add(key)
        kept.append(it)
    return kept


# ----------------------------------------------------------------------------
# main
# ----------------------------------------------------------------------------
def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=None, help="PDF 所在目录，默认 pdf-inbox/")
    ap.add_argument("--only", default=None, help="只处理某个 key（调试用）")
    ap.add_argument("--out", default=None)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--debug", action="store_true", help="打印归类失败/结构诊断")
    args = ap.parse_args()

    global DEBUG
    DEBUG = args.debug

    root = pathlib.Path(__file__).resolve().parent.parent
    src = pathlib.Path(args.src) if args.src else root / "pdf-inbox"
    if not src.is_dir():
        print("[FAIL] 找不到 PDF 目录：%s" % src)
        return 1

    seed_dir = root / "backend" / "src" / "main" / "resources" / "seed"
    out = pathlib.Path(args.out) if args.out else seed_dir / "05-interview-pdf.json"

    stats = Counter()
    raw: list[dict] = []
    for bank in BANKS:
        if args.only and bank["key"] != args.only:
            continue
        hits = sorted(src.glob(bank["glob"]))
        if not hits:
            print("[WARN] 缺少文件：%s" % bank["glob"])
            continue
        if len(hits) > 1:
            print("[WARN] %s 命中 %d 个文件，取第一个：%s"
                  % (bank["glob"], len(hits), hits[0].name))
        p = hits[0]
        got = parse_pdf(p, bank["key"], stats)
        print("  %-12s %-58s -> %4d 道" % (bank["key"], p.name[:58], len(got)))
        raw.extend(got)

    existing = load_existing(seed_dir, out.name)
    kept = dedup(raw, existing, stats)

    for it in kept:
        n = len(it["answer"])
        it["difficulty"] = 1 if n < 300 else (2 if n < 1200 else 3)
        # 大厂面经是真实面试问到的题，标为高频考点；其余不臆测热度
        it["hot"] = 1 if it.get("source_key") == "exp" else 0
        tags = [t for t in (it["source_mid"], it["source_top"]) if t]
        if it.get("source_key") == "exp":
            tags.append("大厂面经")
        it["tags"] = ",".join(dict.fromkeys(
            s.replace(",", " ").replace("，", " ") for s in tags))[:200]

    used = {q["category"] for q in kept}
    doc = {
        "_source": SOURCE,
        "categories": [c for c in NEW_CATEGORIES if c["code"] in used],
        "questions": [{
            "category": q["category"], "title": q["title"],
            "difficulty": q["difficulty"], "hot": q["hot"],
            "tags": q["tags"], "answer": q["answer"],
        } for q in kept],
    }

    print("\n原始候选 %d，可导入 %d" % (len(raw), len(kept)))
    print("丢弃明细:", dict(stats))
    print("分类分布:", dict(Counter(q["category"] for q in kept).most_common()))
    print("新增分类:", [c["code"] for c in doc["categories"]])
    print("导入前题库 %d 题 → 导入后 %d 题" % (len(existing), len(existing) + len(kept)))

    if args.dry_run:
        print("[dry-run] 未写入")
        return 0

    out.write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
    print("已写入 %s (%.1f MB)" % (out, out.stat().st_size / 1048576))
    return 0


if __name__ == "__main__":
    sys.exit(main())
