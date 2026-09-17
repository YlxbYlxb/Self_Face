"""
题库 seed 结构校验。

用法：  python scripts/check_seed.py
退出码：0 = 通过；1 = 发现问题（细节打印在 stdout）

题库是要长期增量维护的内容，靠人眼查字段拼写、分类引用不现实，
所以把规则固化成脚本，改完题库跑一遍。
"""
from __future__ import annotations

import json
import pathlib
import sys
from collections import Counter

ROOT = pathlib.Path(__file__).resolve().parent.parent
SEED_DIR = ROOT / "backend" / "src" / "main" / "resources" / "seed"

REQUIRED_FIELDS = ("title", "answer", "category")
MAX_TITLE = 500
MAX_REPORTED = 60


def main() -> int:
    if not SEED_DIR.is_dir():
        print(f"[FAIL] 找不到题库目录：{SEED_DIR}")
        return 1

    files = sorted(SEED_DIR.glob("*.json"))
    if not files:
        print(f"[FAIL] {SEED_DIR} 下没有任何 json 文件")
        return 1

    problems: list[str] = []
    declared_codes: dict[str, str] = {}
    all_titles: Counter[str] = Counter()
    parsed: list[tuple[pathlib.Path, dict]] = []
    total_questions = 0

    # 第一遍先收齐所有文件声明的分类：题目允许引用别的文档里定义的分类
    for path in files:
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            problems.append(f"{path.name}: JSON 解析失败（第 {e.lineno} 行）：{e.msg}")
            continue
        if not isinstance(data, dict):
            problems.append(f"{path.name}: 顶层应当是对象，实际是 {type(data).__name__}")
            continue
        parsed.append((path, data))

        for cat in data.get("categories", []) or []:
            code = str(cat.get("code") or "").strip()
            name = str(cat.get("name") or "").strip()
            if not code:
                problems.append(f"{path.name}: 有分类缺少 code")
                continue
            if not name:
                problems.append(f"{path.name}: 分类 {code} 缺少 name")
            if code in declared_codes:
                problems.append(
                    f"{path.name}: 分类 code 重复 {code}（已在 {declared_codes[code]} 出现过）")
            declared_codes[code] = path.name

    # 第二遍逐题校验
    for path, data in parsed:
        questions = data.get("questions")
        if not isinstance(questions, list):
            problems.append(f"{path.name}: 缺少 questions 数组")
            continue
        total_questions += len(questions)

        for idx, q in enumerate(questions, start=1):
            where = f"{path.name} 第 {idx} 题"
            if not isinstance(q, dict):
                problems.append(f"{where}: 不是对象")
                continue

            for field in REQUIRED_FIELDS:
                if not str(q.get(field) or "").strip():
                    problems.append(f"{where}: 缺少 {field}")

            title = str(q.get("title") or "").strip()
            if title:
                if len(title) > MAX_TITLE:
                    problems.append(f"{where}: 题干超过 {MAX_TITLE} 字（{len(title)}）")
                all_titles[title] += 1

            difficulty = q.get("difficulty", 2)
            if not isinstance(difficulty, int) or isinstance(difficulty, bool):
                problems.append(f"{where}: difficulty 应当是整数，实际 {difficulty!r}")
            elif not 1 <= difficulty <= 3:
                problems.append(f"{where}: difficulty 必须在 1-3 之间，实际 {difficulty}")

            category = str(q.get("category") or "").strip()
            if category and category not in declared_codes:
                problems.append(f"{where}: 引用了未声明的分类 {category}")

    for title, count in all_titles.items():
        if count > 1:
            problems.append(f"标题重复 {count} 次：{title[:40]}")

    print(f"扫描 {len(files)} 个文件、{len(declared_codes)} 个分类、{total_questions} 道题")
    if problems:
        print(f"\n发现 {len(problems)} 个问题：")
        for p in problems[:MAX_REPORTED]:
            print("  - " + p)
        if len(problems) > MAX_REPORTED:
            print(f"  …还有 {len(problems) - MAX_REPORTED} 个未显示")
        return 1

    print("题库结构校验通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
