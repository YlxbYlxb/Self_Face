-- 八股学习平台建表脚本。全部 IF NOT EXISTS，可重复执行。
-- 库本身由 JDBC URL 的 createDatabaseIfNotExist=true 自动创建。

CREATE TABLE IF NOT EXISTS sys_user (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    username        VARCHAR(32)  NOT NULL,
    password        VARCHAR(100) NOT NULL COMMENT 'BCrypt 散列',
    nickname        VARCHAR(32)  DEFAULT NULL,
    email           VARCHAR(64)  DEFAULT NULL,
    target_cities   VARCHAR(128) DEFAULT NULL COMMENT '求职目标城市，逗号分隔',
    target_position VARCHAR(64)  DEFAULT NULL,
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户';

CREATE TABLE IF NOT EXISTS category (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(64)  NOT NULL,
    code        VARCHAR(64)  NOT NULL,
    description VARCHAR(255) DEFAULT NULL,
    sort_order  INT          DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '题目分类';

CREATE TABLE IF NOT EXISTS question (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    category_id BIGINT       NOT NULL,
    title       VARCHAR(512) NOT NULL COMMENT '题干',
    answer      TEXT COMMENT '参考答案 Markdown',
    difficulty  TINYINT      DEFAULT 2 COMMENT '1 简单 2 中等 3 困难',
    tags        VARCHAR(255) DEFAULT NULL COMMENT '逗号分隔标签',
    hot         TINYINT      DEFAULT 0 COMMENT '1 为高频考点',
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_question_category (category_id),
    KEY idx_question_hot (hot)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '题库';

CREATE TABLE IF NOT EXISTS daily_task (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    user_id     BIGINT   NOT NULL,
    task_date   DATE     NOT NULL,
    question_id BIGINT   NOT NULL,
    seq         INT      DEFAULT 1 COMMENT '当天题单内序号',
    mastery     TINYINT  DEFAULT 0 COMMENT '0 未做 1 不会 2 模糊 3 掌握',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_daily_user_date_question (user_id, task_date, question_id),
    KEY idx_daily_user_date (user_id, task_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '每日题单';

CREATE TABLE IF NOT EXISTS practice_record (
    id           BIGINT   NOT NULL AUTO_INCREMENT,
    user_id      BIGINT   NOT NULL,
    question_id  BIGINT   NOT NULL,
    mastery      TINYINT  NOT NULL COMMENT '1 不会 2 模糊 3 掌握',
    answer_text  TEXT COMMENT '用户口述的答案',
    cost_seconds INT      DEFAULT NULL,
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_record_user_question (user_id, question_id),
    KEY idx_record_user_created (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '刷题记录明细';

-- 复习调度状态：每道题一行，记录 SM-2 的下次复习时间与难度系数。
-- practice_record 是「明细」（每次作答一条，只增不改），review_state 是「聚合」
-- （每题一行，被反复覆盖）。两者职责不同，所以分开存。
CREATE TABLE IF NOT EXISTS review_state (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    user_id          BIGINT        NOT NULL,
    question_id      BIGINT        NOT NULL,
    last_reviewed_at DATETIME      DEFAULT NULL COMMENT '最近一次作答时间',
    next_review_at   DATE          NOT NULL COMMENT '到期日，<= 今天即进入复习队列',
    interval_days    INT           DEFAULT 0 COMMENT '当前复习间隔（天），按 SM-2 逐次拉长',
    ease_factor      DECIMAL(4, 2) DEFAULT 2.50 COMMENT 'SM-2 难度系数 1.30~2.80，越大间隔涨得越快',
    review_count     INT           DEFAULT 0 COMMENT '累计复习次数',
    lapse_count      INT           DEFAULT 0 COMMENT '遗忘次数（答「不会」的次数）',
    created_at       DATETIME      DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_user_question (user_id, question_id),
    KEY idx_review_user_due (user_id, next_review_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '复习调度状态（SM-2）';

-- 模拟面试：一次会话一行。题源可以是一份已解析的简历，也可以是用户自填的岗位。
CREATE TABLE IF NOT EXISTS interview_session (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    user_id       BIGINT      NOT NULL,
    resume_id     BIGINT      DEFAULT NULL COMMENT '作为题源的简历分析 id，可为空',
    role          VARCHAR(64) DEFAULT NULL COMMENT '目标岗位',
    level         VARCHAR(16) DEFAULT 'junior' COMMENT 'junior / mid / senior',
    type          VARCHAR(16) DEFAULT 'technical' COMMENT 'technical / project / comprehensive',
    status        VARCHAR(16) DEFAULT 'RUNNING' COMMENT 'RUNNING / FINISHED',
    max_rounds    INT         DEFAULT 5 COMMENT '计划的问答轮数',
    overall_score INT         DEFAULT NULL COMMENT '面试官总评得分 0-100',
    report_json   MEDIUMTEXT COMMENT '最终报告 JSON',
    started_at    DATETIME    DEFAULT CURRENT_TIMESTAMP,
    finished_at   DATETIME    DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_session_user (user_id, started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '模拟面试会话';

-- 对话轮次。面试官的提问与候选人的回答都记在这里，seq 决定顺序。
CREATE TABLE IF NOT EXISTS interview_turn (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    session_id BIGINT        NOT NULL,
    seq        INT           NOT NULL,
    role       VARCHAR(16)   NOT NULL COMMENT 'interviewer / candidate',
    kind       VARCHAR(16)   DEFAULT 'question' COMMENT 'question / follow_up / answer',
    content    TEXT COMMENT '提问或回答的正文',
    score      INT           DEFAULT NULL COMMENT '面试官对上一次回答的评分 0-100',
    comment    VARCHAR(1024) DEFAULT NULL COMMENT '面试官点评：答得好在哪、漏了什么',
    created_at DATETIME      DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_turn_session (session_id, seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '模拟面试对话轮次';

CREATE TABLE IF NOT EXISTS resume_analysis (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    user_id           BIGINT        NOT NULL,
    file_name         VARCHAR(255)  DEFAULT NULL,
    raw_text          MEDIUMTEXT COMMENT '抽取出的简历原文',
    profile_json      MEDIUMTEXT COMMENT 'LLM 画像 JSON',
    questions_json    MEDIUMTEXT COMMENT 'LLM 预测问题 JSON',
    summary           VARCHAR(512)  DEFAULT NULL,
    status            VARCHAR(16)   DEFAULT 'PENDING',
    error_msg         VARCHAR(1024) DEFAULT NULL,
    model             VARCHAR(64)   DEFAULT NULL,
    prompt_tokens     INT           DEFAULT 0,
    completion_tokens INT           DEFAULT 0,
    created_at        DATETIME      DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_resume_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '简历分析结果';

CREATE TABLE IF NOT EXISTS llm_setting (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    user_id         BIGINT        NOT NULL,
    base_url        VARCHAR(255)  DEFAULT NULL COMMENT 'OpenAI 兼容地址，含 /v1',
    api_key         VARCHAR(512)  DEFAULT NULL,
    model           VARCHAR(64)   DEFAULT NULL,
    temperature     DECIMAL(3, 2) DEFAULT 0.30,
    timeout_seconds INT           DEFAULT 120,
    updated_at      DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_llm_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户自带的 LLM 配置';
