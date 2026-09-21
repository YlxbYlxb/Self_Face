"""一次性清理脚本：移除 seed 答案中指向 PDF 配图的悬空引用。

背景：这些题的答案里有「如下图所示：」「上图的第四阶段」「图一是一个平衡二叉树」
等表述，但配图没有随 PDF 解析进 seed，导致读者读到指路却看不到图。
本脚本只做文本层面的引用清理，不改动任何技术内容。

用法：python scripts/clean-figure-refs.py [--apply]
不带 --apply 时只预览。

注意：这是 import-interview-pdf.py 的**下游步骤**。重跑导入脚本会重新生成 seed、
覆盖掉本脚本的清理结果，所以顺序必须是「先导入、再清理」。
"""
import json
import re
import shutil
import sys
from pathlib import Path

SEED = Path(__file__).resolve().parent.parent / "backend/src/main/resources/seed/05-interview-pdf.json"
BACKUP = Path(__file__).resolve().parent / ".extract-cache/05-interview-pdf.backup.json"

# 统一覆盖四种写法：如下图 / 如下图所示 / 如图 / 如图所示 / 下图 / 下图所示
FIG = r"(?:如下(?:所)?图|如(?:所)?图|下图)(?:所示)?"
# 同上，但额外允许裸「图」和「上图」（仅用于「从图…可以看…」这类上下文已限定的场合）
FIGB = r"(?:如下(?:所)?图|如(?:所)?图|下图|上图|图)(?:所示)?"

# 有序规则表：
#   第一段 = 含上下文的复合场景，逐条给出通顺改写（必须排在最前）
#   第二段 = 引出语，后文自足，把「指向图」改为「指向后文」
#   第三段 = 通用指代，删掉指向图的限定词
#   第四段 = PDF 图片被抽成文本后的占位残留
RULES = [
    # ---------- 复合场景 ----------
    (r"可以看看" + FIG + r"为\s*(\d+)\s*扩充为\s*(\d+)\s*的\s*resize\s*示意图",
     r"可以看看 \1 扩充为 \2 的 resize 过程"),
    (r"上图中，1，2 是大顶堆", "以下三种情况，1、2 是大顶堆"),
    (r"上图中分\s*(\d+)\s*个步骤介绍了", r"流程分为 \1 个步骤："),
    (r"上图中分\s*(\d+)\s*个步骤", r"流程分为 \1 个步骤"),
    (r"在上图所示的情况中", "在这种情况下"),
    (r"如上图所示，", "如下所述，"),
    (r"如上图所示", "如上所述"),
    (r"如上图对", "下面对"),
    (r"对比两个图可以看出", "对比前后可以看出"),
    (r"然后才真正执行上图的流程", "然后才真正执行解析流程"),
    (r"我把 rehash 这三个过程画在了下面这张图", "rehash 主要有三个过程"),
    (r"我通过一张图来解释", "这里解释一下"),
    (r"(?:我)?画了(?:一张|个)图(?:来)?(?:表示|说明|展示)它们的关系", "它们的关系如下"),
    (r"以一张图来表示它们的关系", "它们的关系如下"),
    (r"下面这张图，展示了", "下面这个例子展示了"),
    (r"" + FIG + r"展示了一个层级为\s*(\d+)\s*的跳表", r"下面是一个层级为 \1 的跳表"),
    (r"如上图为例子", "以上述例子"),
    (r"从" + FIGB + r"中?(?:你)?可以看到", "可以看到"),
    (r"从" + FIGB + r"中?(?:你)?可(?:以)?看出", "可以看出"),
    (r"通过这张图你可以看到", "可以看到"),
    (r"" + FIG + r"就是", "下面就是"),
    (r"" + FIG + r"是", "下面是"),
    (r"" + FIG + r"表示", "下面演示"),
    (r"" + FIG + r"展示", "下面展示"),
    (r"比如" + FIG + r"，", "比如下面这个例子，"),
    (r"再来看看" + FIG, "再来看看下面这个例子"),
    (r"会发生" + FIG + r"的过程", "会发生如下的过程"),
    (r"（" + FIG + r"的右下角）", ""),
    (r"[，,]\s*" + FIG + r"(?:左边|右边|左侧|右侧)部分\s*[:：]?", "，如下："),
    (r"^" + FIG + r"(?:左边|右边|左侧|右侧)部分\s*[:：]?", "如下所述："),
    (r"" + FIG + r"(?:左边|右边|左侧|右侧)部分", "如下所述"),
    (r"分布图中", "分布中"),
    (r"图一", "第一种"),
    (r"图二", "第二种"),
    (r"图三", "第三种"),
    (r"图\s*4", "第四种"),
    # ---------- 引出语 ----------
    (r"" + FIG + r"\s*[:：]", "如下："),
    (r"" + FIG + r"\s*(?=\n)", "如下"),
    (r"" + FIG + r"(?=[（，、])", "如下"),
    (r"[，,]?\s*" + FIG + r"\s*[。；]", "。"),
    (r"^\s*" + FIG + r"\s*[:：]?\s*$", ""),
    (r"见下图", "见下文"),
    # ---------- 通用指代 ----------
    (r"上图的?", ""),
    (r"上图中", "其中"),
    (r"下图中", "其中"),
    (r"图中的", ""),
    (r"图中", ""),
    (r"这张图", "这里"),
    (r"^" + FIG + r"$", ""),
    (r"" + FIG, "下文"),
    # ---------- 图片占位残留 ----------
    (r"^\s*(?:img|图片|图像)\s*$", ""),
    (r"^\s*(?:img|图片|图像)\s*\n", ""),
]


def clean(text: str, log: list, title: str):
    if not text:
        return text
    for pat, rep in RULES:
        new = re.sub(pat, rep, text, flags=re.MULTILINE)
        if new != text:
            log.append((title, pat, rep))
            text = new
    # 收拾因删除留下的标点脏乱
    text = re.sub(r"[。；]\s*[，、]+", "。", text)
    text = re.sub(r"：[ \t]*[，、]", "，", text)
    text = re.sub(r"[，、]\s*([。！？；])", r"\1", text)
    text = re.sub(r"[，、]{2,}", "，", text)
    text = re.sub(r"：\s*：", "：", text)
    text = re.sub(r"如下[：:]\s*如下[：:]", "如下：", text)
    # 相邻两行都以「如下」开头时合并，避免「如下：／如下所述，」叠用
    text = re.sub(r"如下[：:]\s*\n+\s*如下所述[，,]?\s*", "如下：\n\n", text)
    text = re.sub(r"如下[：:]\s*\n+\s*如下[：:]", "如下：\n\n", text)
    text = re.sub(r"(?m)^[，、。；：]+", "", text)
    text = re.sub(r"[ \t]+\n", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text


def main():
    apply = "--apply" in sys.argv
    d = json.loads(SEED.read_text(encoding="utf-8"))
    key = "questions" if isinstance(d, dict) and "questions" in d else None
    qs = d[key] if key else d

    log, changed = [], []
    for q in qs:
        before = q.get("answer") or ""
        after = clean(before, log, q["title"])
        if after != before:
            q["answer"] = after
            changed.append((q["title"], before, after))

    print(f"改写题数：{len(changed)} / {len(qs)}")
    print(f"命中规则次数：{len(log)}\n")

    counts = {}
    for _, pat, rep in log:
        counts[(pat, rep)] = counts.get((pat, rep), 0) + 1
    print("=== 各规则命中次数 ===")
    for (pat, rep), n in sorted(counts.items(), key=lambda x: -x[1]):
        print(f"{n:>3}x  {pat[:52]:<54} → {rep!r}")

    resid = [(q["title"], m.group(0)) for q in qs
             for m in re.finditer(r"如下图|下图|上图|如图|这张图|图中|见下图", q.get("answer") or "")]
    print(f"\n=== 残留悬空引用：{len(resid)} 处 ===")
    for t, w in resid:
        print(f"  - [{w}] {t[:56]}")
    for kw in ("img", "图片", "图像"):
        left = sum((q.get("answer") or "").count(kw) for q in qs)
        print(f"残留 {kw}: {left} 次")

    if apply:
        BACKUP.parent.mkdir(parents=True, exist_ok=True)
        if not BACKUP.exists():
            shutil.copy2(SEED, BACKUP)
            print(f"\n已备份 → {BACKUP}")
        SEED.write_text(json.dumps(d, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"已写回 → {SEED}")
    else:
        print("\n（预览模式，未写盘。加 --apply 生效）")


if __name__ == "__main__":
    main()
