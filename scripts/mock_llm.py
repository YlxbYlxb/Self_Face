"""
本地 mock 的 OpenAI 兼容服务，用于在没有真实 API Key 的情况下验证 AI 链路。

为什么需要它：JD 分析、简历分析这类功能的成败取决于「模型返回什么」，
但真实模型的输出不可控、要花钱、还慢。用一个固定返回的 mock 服务，
就能把「请求 → 解析 → 落库 → 展示」这一段完整跑通并断言，
把模型的不确定性隔离在链路之外。

用法：
    python scripts/mock_llm.py            # 监听 127.0.0.1:18080
然后把用户的 LLM 配置指向 http://127.0.0.1:18080/v1 即可。

关键词故意混了三类：题库里肯定有的（JVM/MySQL/Redis）、大概率没有的（Kubernetes）、
以及带下划线和括号的写法，用来验证检索不会因为符号而漏召回。
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = 18080

# JD 抽取器的预期输出
JD_PAYLOAD = {
    "role": "Java 后端开发实习生",
    "summary": "看重 Java 基础、数据库与缓存，有容器化经验加分",
    "keywords": [
        {"term": "JVM", "category": "语言", "weight": "核心"},
        {"term": "MySQL", "category": "数据库", "weight": "核心"},
        {"term": "Redis", "category": "中间件", "weight": "核心"},
        {"term": "Kubernetes", "category": "工具", "weight": "加分"},
    ],
}

# 简历画像的预期输出（保持最小可用，够简历链路跑通即可）
RESUME_PROFILE = {
    "name": "张同学",
    "education": "本科在读",
    "targetRole": "Java 后端开发",
    "yearsOfExperience": "应届",
    "skills": [{"name": "Java", "level": "熟悉", "evidence": "项目中使用"}],
    "projects": [{"name": "示例项目", "role": "独立开发", "techStack": ["Java"],
                 "highlights": [], "weakPoints": []}],
    "strengths": [], "risks": [],
}

RESUME_QUESTIONS = {
    "summary": "基础尚可，需要补充项目量化描述。",
    "interviewFocus": ["Java 基础"],
    "groups": [{"name": "Java 基础", "reason": "简历提到", "questions": [
        {"question": "HashMap 的扩容机制？", "type": "基础", "difficulty": 2,
         "why": "考察基础", "followUps": ["为什么是 2 的幂"], "keyPoints": ["扩容因子"],
         "bankQuestionId": 0}]}],
    "preparationPlan": ["先补基础"],
}


# 模拟面试的三个阶段
INTERVIEW_OPENING = {
    "question": "你简历里提到做过一个检索类项目，先说说它的整体架构是怎么设计的？",
    "kind": "question",
}

INTERVIEW_EVAL = {
    "score": 72,
    "comment": "把检索链路讲清楚了，但没说清为什么选这个向量模型而不是别的，选型依据是空的。",
    "gaps": ["技术选型的判断依据"],
    "nextQuestion": "那你们是怎么衡量检索效果的？有没有做过对比？",
    "kind": "follow_up",
}

INTERVIEW_REPORT = {
    "overallScore": 74,
    "verdict": "基础扎实、能把项目讲明白，但技术选型的原因说不清，属于「会用但没想过为什么」。",
    "dimensions": [
        {"name": "基础知识", "score": 78, "comment": "核心概念准确"},
        {"name": "表达结构", "score": 70, "comment": "偏流水账，缺重点"},
        {"name": "项目深度", "score": 74, "comment": "能讲链路，选型理由薄弱"},
    ],
    "strengths": ["能把复杂链路的顺序讲清楚"],
    "weaknesses": [
        {"point": "技术选型说不清原因", "suggestion": "给每个关键组件准备一句「为什么是它、为什么不是另一个」"}
    ],
    "suggestedTopics": ["向量模型选型", "检索效果评估方法"],
}


class Handler(BaseHTTPRequestHandler):
    """按请求内容判断该回哪个阶段的结果，避免维护多套 mock 服务。"""

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length).decode("utf-8", "replace") if length else ""

        if "请提出你的第一个问题" in raw:
            payload = INTERVIEW_OPENING
        elif "请点评这个回答" in raw:
            payload = INTERVIEW_EVAL
        elif "请以面试官身份给出评估报告" in raw:
            payload = INTERVIEW_REPORT
        elif "技术考点" in raw:
            payload = JD_PAYLOAD
        elif "画像 JSON" in raw and "简历原文" in raw:
            payload = RESUME_PROFILE
        elif "简历原文" in raw:
            payload = RESUME_QUESTIONS
        else:
            payload = JD_PAYLOAD

        body = {
            "choices": [{"index": 0, "message": {"role": "assistant",
                                                 "content": json.dumps(payload, ensure_ascii=False)},
                         "finish_reason": "stop"}],
            "model": "mock-model",
            "usage": {"prompt_tokens": 128, "completion_tokens": 64},
        }
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        """静默：mock 服务的访问日志没有价值，留着只会刷屏。"""


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else PORT
    server = HTTPServer(("127.0.0.1", port), Handler)
    print(f"mock LLM 已启动：http://127.0.0.1:{port}/v1  (Ctrl+C 退出)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止")


if __name__ == "__main__":
    main()
