CREATE TABLE IF NOT EXISTS tenant (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    principal       VARCHAR(256) NOT NULL,
    keytab_path     VARCHAR(512),
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    description     VARCHAR(1024),
    default_database VARCHAR(128),
    create_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- V3 (PR-0 of 10-item optimization): auth_name 是 Principal 的可读别名，
-- 存在 UNIQUE 约束，与 principal 一一对应；last_test_at/last_test_ok 让
-- UI 可以直接显示最近一次认证测试结果。
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS auth_name VARCHAR(256);
ALTER TABLE tenant ADD CONSTRAINT IF NOT EXISTS uq_tenant_auth_name UNIQUE (auth_name);
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS last_test_at TIMESTAMP;
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS last_test_ok BOOLEAN;

CREATE TABLE IF NOT EXISTS script (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    name              VARCHAR(128) NOT NULL,
    display_name      VARCHAR(256),
    category          VARCHAR(64),
    description       VARCHAR(1024),
    script_path       VARCHAR(512),
    timeout_seconds   INT NOT NULL DEFAULT 600, -- 与 ScriptBoxProperties.defaultTimeoutSeconds 同步
    enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    favorite          BOOLEAN NOT NULL DEFAULT FALSE,
    default_tenant_id BIGINT,
    create_time       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE script ADD COLUMN IF NOT EXISTS favorite BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE script ADD COLUMN IF NOT EXISTS default_tenant_id BIGINT;
-- V2: risk level gates execution (READ_ONLY / WRITE / DANGEROUS). DANGEROUS
-- requires the caller to send CONFIRM as a token, otherwise the executor
-- refuses to launch the process.
ALTER TABLE script ADD COLUMN IF NOT EXISTS risk_level VARCHAR(16) NOT NULL DEFAULT 'READ_ONLY';
-- V2: anti-duplicate flag. When false (default), the executor refuses to start
-- a second execution of this script while another is still running.
ALTER TABLE script ADD COLUMN IF NOT EXISTS allow_concurrent BOOLEAN NOT NULL DEFAULT FALSE;
-- Pre-execution checks config (free-form JSON; consumed by PrecheckService).
ALTER TABLE script ADD COLUMN IF NOT EXISTS precheck_config_json VARCHAR(4096);

CREATE TABLE IF NOT EXISTS script_param (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    script_id       BIGINT NOT NULL,
    name            VARCHAR(64) NOT NULL,
    label           VARCHAR(256),
    type            VARCHAR(16) NOT NULL,
    default_value   VARCHAR(2048),
    options         VARCHAR(2048),
    required        BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order      INT NOT NULL DEFAULT 0,
    placeholder     VARCHAR(1024),
    help_text       VARCHAR(1024),
    visible_when_json VARCHAR(512),
    create_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE script_param ADD COLUMN IF NOT EXISTS placeholder VARCHAR(1024);
ALTER TABLE script_param ADD COLUMN IF NOT EXISTS help_text VARCHAR(1024);
-- V2: simple JSON visibility rule for conditional params.
ALTER TABLE script_param ADD COLUMN IF NOT EXISTS visible_when_json VARCHAR(512);
-- V3 (PR-0): sensitive 字段控制是否将参数值持久化到 localStorage 草稿。
-- 服务端不参与脱敏（执行时仍按原值传），仅前端草稿写入会跳过 sensitive=true。
ALTER TABLE script_param ADD COLUMN IF NOT EXISTS sensitive BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_param_script_id ON script_param(script_id);

CREATE TABLE IF NOT EXISTS execution_history (
    id                  BIGINT PRIMARY KEY,
    script_id           BIGINT,
    script_name         VARCHAR(256),
    tenant_id           BIGINT,
    tenant_name         VARCHAR(256),
    parameters_json     VARCHAR(4096),
    status              VARCHAR(32),
    success             BOOLEAN,
    timeout             BOOLEAN NOT NULL DEFAULT FALSE,
    exit_code           INT,
    start_time          TIMESTAMP NOT NULL,
    end_time            TIMESTAMP,
    duration_ms         BIGINT,
    stdout_path         VARCHAR(1024),
    stderr_path         VARCHAR(1024),
    execution_dir       VARCHAR(1024),
    result_json_path    VARCHAR(1024),
    summary             VARCHAR(4096),
    result_json         VARCHAR(8192),
    batch_id            VARCHAR(64),
    batch_row_index     INT,
    scenario_id         BIGINT,
    scenario_step_no    INT
);
CREATE INDEX IF NOT EXISTS idx_history_script_id ON execution_history(script_id);
CREATE INDEX IF NOT EXISTS idx_history_tenant_id ON execution_history(tenant_id);
CREATE INDEX IF NOT EXISTS idx_history_start_time ON execution_history(start_time);
ALTER TABLE execution_history ADD COLUMN IF NOT EXISTS batch_id VARCHAR(64);
ALTER TABLE execution_history ADD COLUMN IF NOT EXISTS batch_row_index INT;
ALTER TABLE execution_history ADD COLUMN IF NOT EXISTS scenario_id BIGINT;
ALTER TABLE execution_history ADD COLUMN IF NOT EXISTS scenario_step_no INT;
CREATE INDEX IF NOT EXISTS idx_history_batch_id ON execution_history(batch_id);
-- V2: SHA-256 of the script body at the time this execution started. Lets
-- the UI detect when the script has been edited since this run.
ALTER TABLE execution_history ADD COLUMN IF NOT EXISTS script_sha256 VARCHAR(128);
-- V2: full execution context snapshot (params + body + tenant + risk flags)
-- captured at start, used by the "re-run as it ran" button.
ALTER TABLE execution_history ADD COLUMN IF NOT EXISTS snapshot_json VARCHAR(16384);
-- V3 (PR-0): 异步执行 / 重启恢复 / 历史重跑关联
-- interrupted_reason: 服务重启时把 RUNNING 行扫成 INTERRUPTED 留下的备注
-- parent_execution_id: 历史重跑 / 批量行重试时，指向"被重跑"的那次
ALTER TABLE execution_history ADD COLUMN IF NOT EXISTS interrupted_reason VARCHAR(256);
ALTER TABLE execution_history ADD COLUMN IF NOT EXISTS parent_execution_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_history_parent ON execution_history(parent_execution_id);
CREATE INDEX IF NOT EXISTS idx_history_status ON execution_history(status);

CREATE TABLE IF NOT EXISTS preset_variable (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(64) NOT NULL,
    "value"         VARCHAR(2048),
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    description     VARCHAR(1024),
    create_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS file_upload (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    execution_id    BIGINT,
    param_name      VARCHAR(64),
    original_name   VARCHAR(256),
    server_path     VARCHAR(1024) NOT NULL,
    content_type    VARCHAR(128),
    bytes           BIGINT,
    uploaded_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_file_upload_exec ON file_upload(execution_id);

CREATE TABLE IF NOT EXISTS script_preset (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    script_id       BIGINT NOT NULL,
    name            VARCHAR(128) NOT NULL,
    params_json     VARCHAR(4096),
    description     VARCHAR(1024),
    create_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_preset_script_id ON script_preset(script_id);

CREATE TABLE IF NOT EXISTS script_version (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    script_id      BIGINT NOT NULL,
    version_no     INT NOT NULL,
    script_content TEXT NOT NULL,
    remark         VARCHAR(1024),
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_version_script_id ON script_version(script_id, version_no);

CREATE TABLE IF NOT EXISTS global_variable (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    variable_key    VARCHAR(128) NOT NULL,
    variable_value  VARCHAR(2048),
    description     VARCHAR(1024),
    sensitive       BOOLEAN NOT NULL DEFAULT FALSE,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    create_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_global_variable_key ON global_variable(variable_key);

CREATE TABLE IF NOT EXISTS scenario (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    description   VARCHAR(1024),
    category      VARCHAR(128),
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    create_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS scenario_step (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    scenario_id          BIGINT NOT NULL,
    step_no              INT NOT NULL,
    script_id            BIGINT NOT NULL,
    preset_id            BIGINT,
    continue_on_failure  BOOLEAN NOT NULL DEFAULT FALSE,
    create_time          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_step_scenario_id ON scenario_step(scenario_id, step_no);

-- V2: built-in script templates surfaced in the "create from template" UI.
-- code is the stable handle (kebab-case, used in the API path); name and
-- description are user-facing. content is the literal .sh body; params_json
-- is an optional JSON array of ScriptParam specs the UI pre-populates when
-- the user picks this template.
CREATE TABLE IF NOT EXISTS script_template (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    code            VARCHAR(64) NOT NULL,
    name            VARCHAR(128) NOT NULL,
    category        VARCHAR(64),
    description     VARCHAR(1024),
    content         TEXT NOT NULL,
    params_json     VARCHAR(4096),
    sort_order      INT NOT NULL DEFAULT 0,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    create_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_template_code ON script_template(code);

-- V2: per-execution artifact registry. The script can write anything under
-- $ARTIFACT_DIR during its run; we scan that directory after the run
-- completes and register each regular file as a row here. name is the
-- relative path inside the execution directory (always
-- "artifacts/<filename>"); path is the absolute on-disk path; size_bytes
-- is the file size at scan time; sha256 is the hex digest used to
-- de-duplicate on re-scan; mime_type is best-effort.
CREATE TABLE IF NOT EXISTS execution_artifact (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    execution_id    BIGINT NOT NULL,
    name            VARCHAR(512) NOT NULL,
    path            VARCHAR(1024) NOT NULL,
    size_bytes      BIGINT NOT NULL DEFAULT 0,
    sha256          VARCHAR(128),
    mime_type       VARCHAR(128),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_artifact_exec ON execution_artifact(execution_id);

-- V2: simple key/value settings table used by the cleanup preview flow to
-- override the application.yml defaults without restart. Keys are
-- dotted namespaced (e.g. cleanup.historyDays); values are strings
-- coerced on read. UpdatedTime lets the UI show 'last edited'.
CREATE TABLE IF NOT EXISTS system_setting (
    setting_key   VARCHAR(128) PRIMARY KEY,
    setting_value VARCHAR(1024),
    description   VARCHAR(512),
    update_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- V2: audit log of every manual cleanup the operator ran. One row per
-- execute call (regardless of how many deletes happened). Keeps the
-- 'recent cleanup' list at the bottom of Settings → Data Cleanup, and
-- gives a paper trail when something disappears.
CREATE TABLE IF NOT EXISTS cleanup_history (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    preview_id          VARCHAR(64),
    retention_json      VARCHAR(1024) NOT NULL,
    result              VARCHAR(16) NOT NULL,                  -- SUCCESS / PARTIAL / FAILED
    execution_deleted   INT NOT NULL DEFAULT 0,
    artifact_deleted    INT NOT NULL DEFAULT 0,
    log_deleted         INT NOT NULL DEFAULT 0,
    history_deleted     INT NOT NULL DEFAULT 0,
    bytes_freed         BIGINT NOT NULL DEFAULT 0,
    skipped_count       INT NOT NULL DEFAULT 0,
    failed_count        INT NOT NULL DEFAULT 0,
    message             VARCHAR(2048)
);
CREATE INDEX IF NOT EXISTS idx_cleanup_history_created_at ON cleanup_history(created_at);