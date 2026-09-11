-- MyBatis-Plus auto-generates table names from @TableName, so we explicitly create them here.
-- Spring Boot runs schema.sql on every start when spring.sql.init.mode=always.
-- IF NOT EXISTS keeps restarts idempotent.

CREATE TABLE IF NOT EXISTS tenant (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    principal       VARCHAR(256),
    keytab_path     VARCHAR(512),
    default_database VARCHAR(128),
    description     VARCHAR(1024),
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    create_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS script (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    name               VARCHAR(128) NOT NULL,
    display_name       VARCHAR(256),
    category           VARCHAR(128),
    description        VARCHAR(1024),
    script_path        VARCHAR(512) NOT NULL,
    timeout_seconds    INT NOT NULL DEFAULT 600,
    enabled            BOOLEAN NOT NULL DEFAULT TRUE,
    favorite           BOOLEAN NOT NULL DEFAULT FALSE,
    default_tenant_id  BIGINT,
    create_time        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE script ADD COLUMN IF NOT EXISTS favorite BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE script ADD COLUMN IF NOT EXISTS default_tenant_id BIGINT;

CREATE TABLE IF NOT EXISTS script_param (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    script_id      BIGINT NOT NULL,
    name           VARCHAR(128) NOT NULL,
    label          VARCHAR(256),
    type           VARCHAR(32)  NOT NULL,
    default_value  VARCHAR(1024),
    options        VARCHAR(2048),
    required       BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order     INT NOT NULL DEFAULT 0,
    placeholder    VARCHAR(1024),
    help_text      VARCHAR(1024)
);
CREATE INDEX IF NOT EXISTS idx_script_param_script_id ON script_param(script_id);
ALTER TABLE script_param ADD COLUMN IF NOT EXISTS placeholder VARCHAR(1024);
ALTER TABLE script_param ADD COLUMN IF NOT EXISTS help_text VARCHAR(1024);

CREATE TABLE IF NOT EXISTS execution_history (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    script_id       BIGINT,
    script_name     VARCHAR(256),
    tenant_id       BIGINT,
    tenant_name     VARCHAR(256),
    parameters_json VARCHAR(4096),
    success         BOOLEAN NOT NULL DEFAULT FALSE,
    exit_code       INT,
    timeout         BOOLEAN NOT NULL DEFAULT FALSE,
    duration_ms     BIGINT,
    stdout_path     VARCHAR(512),
    stderr_path     VARCHAR(512),
    execution_dir   VARCHAR(512),
    start_time      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    end_time        TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_history_start_time ON execution_history(start_time);

-- V1.5 additions (all backward-compatible ADD COLUMN IF NOT EXISTS) ----

ALTER TABLE execution_history ADD COLUMN IF NOT EXISTS batch_id VARCHAR(64);
ALTER TABLE execution_history ADD COLUMN IF NOT EXISTS batch_row_index INT;
ALTER TABLE execution_history ADD COLUMN IF NOT EXISTS scenario_id BIGINT;
ALTER TABLE execution_history ADD COLUMN IF NOT EXISTS scenario_step_no INT;
ALTER TABLE execution_history ADD COLUMN IF NOT EXISTS result_json_path VARCHAR(512);
ALTER TABLE execution_history ADD COLUMN IF NOT EXISTS status VARCHAR(32);
ALTER TABLE execution_history ADD COLUMN IF NOT EXISTS result_json TEXT;
CREATE INDEX IF NOT EXISTS idx_history_batch_id ON execution_history(batch_id);
CREATE INDEX IF NOT EXISTS idx_history_status ON execution_history(status);

ALTER TABLE script ADD COLUMN IF NOT EXISTS precheck_config_json VARCHAR(4096);

-- V2 Reliability additions (Phase 1: risk levels + concurrent flag) ----
ALTER TABLE script ADD COLUMN IF NOT EXISTS risk_level VARCHAR(16) NOT NULL DEFAULT 'READ_ONLY';
ALTER TABLE script ADD COLUMN IF NOT EXISTS allow_concurrent BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS script_preset (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    script_id    BIGINT NOT NULL,
    name         VARCHAR(128) NOT NULL,
    params_json  VARCHAR(4096),
    description  VARCHAR(1024),
    create_time  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
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
CREATE INDEX IF NOT EXISTS idx_global_var_key ON global_variable(variable_key);

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