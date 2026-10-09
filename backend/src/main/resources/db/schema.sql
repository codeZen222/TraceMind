-- ============================================================
-- TraceMind · MySQL 建表脚本
-- 库名 tracemind，字符集 utf8mb4
-- 依据《TraceMind-后端规格说明书.md》第 4.1 节
-- 说明：文档标题写「6 表」，实际列了 7 张（含 import_task），本脚本按 7 张建
-- ============================================================

CREATE DATABASE IF NOT EXISTS tracemind
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_general_ci;

USE tracemind;

-- ------------------------------------------------------------
-- 1. 项目表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS project (
  id           VARCHAR(32)  NOT NULL COMMENT '如 p-001',
  name         VARCHAR(64)  NOT NULL,
  description  VARCHAR(255) DEFAULT NULL,
  status       VARCHAR(16)  NOT NULL DEFAULT 'DEVELOPING' COMMENT 'DEVELOPING | DEPLOYED',
  language     VARCHAR(32)  DEFAULT 'Java / Spring Boot',
  source       VARCHAR(8)   NOT NULL DEFAULT 'PATH' COMMENT 'UPLOAD | PATH',
  log_path     VARCHAR(512) DEFAULT NULL,
  file_count   INT          DEFAULT NULL,
  health_url   VARCHAR(512) DEFAULT NULL,
  source_root  VARCHAR(512) DEFAULT NULL,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id), KEY idx_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ------------------------------------------------------------
-- 2. 监控任务表（WatchService 采集任务的元数据）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS log_monitor_task (
  task_id          VARCHAR(32)  NOT NULL,
  project_id       VARCHAR(32)  NOT NULL,
  running          TINYINT(1)   NOT NULL DEFAULT 0,
  file_path        VARCHAR(512) NOT NULL,
  offset           BIGINT       NOT NULL DEFAULT 0 COMMENT '字节偏移（Redis 为主）',
  file_size        BIGINT       NOT NULL DEFAULT 0,
  last_read_at     DATETIME     DEFAULT NULL,
  lines_read       BIGINT       NOT NULL DEFAULT 0,
  errors_detected  INT          NOT NULL DEFAULT 0,
  rule_matched     INT          NOT NULL DEFAULT 0,
  agent_triggered  INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (task_id), KEY idx_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ------------------------------------------------------------
-- 3. 诊断会话表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_session (
  session_id       VARCHAR(32)  NOT NULL,
  project_id       VARCHAR(32)  NOT NULL,
  status           VARCHAR(16)  NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING|SUCCEEDED|FAILED|DEGRADED',
  trigger_log_id   VARCHAR(64)  DEFAULT NULL,
  started_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at      DATETIME     DEFAULT NULL,
  total_cost_ms    BIGINT       DEFAULT NULL,
  llm_used         TINYINT(1)   NOT NULL DEFAULT 0,
  matched_analyzer VARCHAR(64)  DEFAULT NULL,
  root_cause       TEXT         DEFAULT NULL,
  suggestion       TEXT         DEFAULT NULL,
  confidence       DECIMAL(3,2) DEFAULT NULL,
  tool_call_count  INT          DEFAULT 0,
  result_json      JSON         DEFAULT NULL COMMENT '完整 result（evidence/relatedLogIds 等）',
  PRIMARY KEY (session_id), KEY idx_project (project_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ------------------------------------------------------------
-- 4. 诊断事件表（WS 推送前 MUST 先落库，断线重连靠它回放）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_event (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  session_id VARCHAR(32) NOT NULL,
  seq        INT         NOT NULL,
  event_type VARCHAR(16) NOT NULL COMMENT 'stage|rule_hit|tool_call|tool_result|token|thinking|result|error',
  payload    JSON        NOT NULL,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_session_seq (session_id, seq),
  KEY idx_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ------------------------------------------------------------
-- 5. 追问消息表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chat_message (
  id         VARCHAR(32) NOT NULL,
  session_id VARCHAR(32) NOT NULL,
  role       VARCHAR(8)  NOT NULL COMMENT 'user | assistant',
  content    TEXT        NOT NULL,
  ts         BIGINT      NOT NULL,
  PRIMARY KEY (id), KEY idx_session (session_id, ts)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ------------------------------------------------------------
-- 6. 复盘报告表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS report (
  id              VARCHAR(32) NOT NULL,
  project_id      VARCHAR(32) NOT NULL,
  title           VARCHAR(128) NOT NULL,
  session_ids     JSON        NOT NULL,
  markdown        LONGTEXT    NOT NULL,
  log_count       INT        DEFAULT 0,
  diagnosis_count INT        DEFAULT 0,
  created_at      DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), KEY idx_project (project_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ------------------------------------------------------------
-- 7. 历史日志导入任务表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS import_task (
  task_id    VARCHAR(32) NOT NULL,
  project_id VARCHAR(32) NOT NULL,
  total      INT NOT NULL DEFAULT 0,
  processed  INT NOT NULL DEFAULT 0,
  status     VARCHAR(8) NOT NULL DEFAULT 'RUNNING',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ------------------------------------------------------------
-- 8. 错误基线表（新错误雷达的「已见指纹」）
--    project_id + cluster_id 唯一，就是「判新」的落点：INSERT 冲突 = 已见过
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS error_baseline (
  id            BIGINT      NOT NULL AUTO_INCREMENT,
  project_id    VARCHAR(32) NOT NULL,
  cluster_id    VARCHAR(64) NOT NULL COMMENT 'Drain3 聚类 id，即错误指纹',
  first_seen_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次出现（git blame 联动要用）',
  last_seen_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  acknowledged  TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否已读（前端确认后不再高亮）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_cluster (project_id, cluster_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
