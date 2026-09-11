# BigData Script Box — Reliability V2 Plan

本阶段在已有 V1.5 基础上增加 10 项可靠性 / 可控性 / 可恢复性优化。
不推翻现有架构，只做兼容增量。

## 当前已有能力（基线）

- 脚本 CRUD + Multipart 上传 + 持久化到 `data/scripts/{id}/script.sh`
- 动态参数（text/number/select/boolean/date/textarea/file）
- 租户 + Keytab + mock/real 模式
- PreCheck（kerberos / commands / files / writable dirs）
- ProcessBuilder 执行 Shell + 超时 + destroyForcibly
- stdout / stderr / result.json
- 执行历史 status: SUCCESS / FAILED / TIMEOUT / PRECHECK_FAILED
- 参数 Preset / 脚本 Version / Scenario / Batch / Dry Run
- 文件参数（两段式上传）
- 全局变量（敏感脱敏）
- 导入 / 导出 .zip
- Vue 3 + Element Plus SPA，单 JAR 部署

基线测试：67 tests passing。

## 本阶段 10 项优化

| #  | 功能                          | 关键改动                                                                                                                  |
|----|-------------------------------|---------------------------------------------------------------------------------------------------------------------------|
| 11 | 脚本风险等级 + 危险确认       | Script.riskLevel ∈ {READ_ONLY, WRITE, DANGEROUS}；DANGEROUS 执行需 confirm_token=CONFIRM                                 |
| 12 | 条件参数 / 联动动态表单        | ScriptParam.visibleWhenJson `{param, operator: equals\|notEquals, value}`；前端隐藏；后端不依赖隐藏字段                  |
| 13 | 执行中取消任务                | RunningExecution 注册表 + status=RUNNING/CANCELLED；POST /api/executions/{id}/cancel → destroy + descendants            |
| 14 | 防重复执行 + 全局并发上限      | Script.allowConcurrent (默认 false)；scriptId+tenantId 重复检查；bigdata.execution.max-concurrent (默认 5) Semaphore  |
| 15 | 日志搜索 / 高亮 / 下载        | 前端关键字过滤 + 快捷 chips（ERROR / WARN / Exception / Caused by / FAILED）；上下文（默认 0）；下载受控               |
| 16 | 脚本模板                       | script_template 表 + 内置 5 个模板（普通 / Spark SQL / HDFS / YARN / Kerberos）；从模板创建 Script                    |
| 17 | 执行快照                       | ExecutionHistory.snapshotJson + scriptSha256 + scriptContentSnapshot；敏感变量脱敏；re-run 仍用当前 Script             |
| 18 | Shell 保存前语法检查           | `bash -n` 临时文件检测；exit!=0 拒绝保存；可选 shellcheck 提示                                                            |
| 19 | 执行产物 (Artifact)            | ARTIFACT_DIR 环境变量；execution_artifact 表；scan 不递归；max 50 文件 / 50MB / 200MB；路径穿越防御                    |
| 20 | 自动清理                       | bigdata.retention.* 配置；system_setting 表覆盖；@Scheduled 每日凌晨；preview + 立即清理；跳过 RUNNING               |

## 数据库兼容升级（schema.sql 新增 ALTER）

```sql
ALTER TABLE script ADD COLUMN IF NOT EXISTS risk_level VARCHAR(16) NOT NULL DEFAULT 'READ_ONLY';
ALTER TABLE script ADD COLUMN IF NOT EXISTS allow_concurrent BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE script_param ADD COLUMN IF NOT EXISTS visible_when_json VARCHAR(512);

ALTER TABLE execution_history ADD COLUMN IF NOT EXISTS snapshot_json TEXT;
ALTER TABLE execution_history ADD COLUMN IF NOT EXISTS script_sha256 VARCHAR(64);
ALTER TABLE execution_history ADD COLUMN IF NOT EXISTS script_content_snapshot TEXT;

CREATE TABLE IF NOT EXISTS script_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    category VARCHAR(128),
    description VARCHAR(1024),
    shell_content TEXT NOT NULL,
    params_json TEXT,
    built_in BOOLEAN NOT NULL DEFAULT TRUE,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_script_template_code ON script_template(code);

CREATE TABLE IF NOT EXISTS execution_artifact (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    execution_id BIGINT NOT NULL,
    file_name VARCHAR(256) NOT NULL,
    relative_path VARCHAR(512) NOT NULL,
    file_size BIGINT NOT NULL,
    content_type VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_artifact_execution ON execution_artifact(execution_id);

CREATE TABLE IF NOT EXISTS system_setting (
    setting_key VARCHAR(128) NOT NULL PRIMARY KEY,
    setting_value VARCHAR(1024),
    description VARCHAR(512),
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

不删除任何已有数据。`data/db/scriptbox.mv.db` 继续使用。

## 后端类变更

| 新增类                          | 作用                                                                                  |
|--------------------------------|---------------------------------------------------------------------------------------|
| RiskLevel enum                 | READ_ONLY / WRITE / DANGEROUS                                                       |
| RunningExecutionRegistry       | ConcurrentHashMap；register/unregister/get                                           |
| ExecutionArtifactService       | 扫描 ARTIFACT_DIR；DB 持久化；安全下载                                              |
| ArtifactScanner                | 文件枚举 + 大小/数量限制；Path 防御                                                 |
| ScriptTemplateService          | 模板 CRUD + seed；fromTemplate 创建 Script                                          |
| ShellValidator                 | bash -n；可选 shellcheck                                                            |
| SnapshotService                | 生成 snapshotJson + SHA-256；脱敏敏感变量                                           |
| RetentionService               | @Scheduled 每日清理；预览 + 立即执行                                                |
| SystemSettingService           | system_setting 读写                                                                  |
| ExecutionController 新接口     | /cancel /artifacts /artifacts/{id} /validate                                        |

## 前端页面变更

| View          | 变更                                                                                     |
|---------------|------------------------------------------------------------------------------------------|
| ExecuteView   | 危险确认 Dialog；运行中显示「终止执行」按钮；日志搜索 + 高亮 + 上下文；执行产物 Tab       |
| ScriptCard    | 风险等级 Tag（轻量 chip）                                                                  |
| ParamForm     | 条件参数：visibleWhen 不满足时整行 el-form-item 隐藏 + 不写入 v-model                     |
| ScriptEdit    | 新增「风险等级」「允许并发」「显示条件」编辑控件；新增「从模板创建」入口                  |
| HistoryView   | 状态过滤增加 CANCELLED；详情面板：参数 / stdout / stderr / 执行快照 / 执行产物            |
| SettingsView  | 新增「数据清理」区块（只读展示当前策略 + 立即清理按钮）                                   |
| 新增 utils/labels.js | RISK_LEVEL 字典 + 中文 label + tag type                                          |
| 新增 api/templates.js | 模板列表 + 从模板创建 Script                                                       |

## 安全策略

- 不暴露 `bash -c`；ProcessBuilder 仅用数组参数。
- 脚本目录受控：`data/scripts/{id}/script.sh` 必须位于 scriptsDir 内。
- Keytab 永远不入 H2，仅落盘 + 600 权限。
- Sensitive 全局变量在 dry-run / preview / snapshot 中脱敏。
- 日志下载仅按 `executionId` 查表；不接受客户端路径参数。
- Artifact 下载走 `executionArtifact.id`；下载时校验 realPath 仍位于 executionDir/artifacts 内。
- 取消执行通过 ConcurrentHashMap 锁定 executionId → process 关系；不会误杀其他 execution。

## 测试策略

为每项功能补一个集成测试（基于 BaseIntegrationTest）：
- RiskLevelExecutionTest
- ConditionalParamTest
- ExecutionCancellationTest
- ConcurrencyControlTest
- LogDownloadSecurityTest
- ScriptTemplateTest
- ExecutionSnapshotTest
- ShellValidationTest
- ArtifactTest
- RetentionCleanupTest

mock-scripts 增加：
- `long-running.sh` —— sleep 60 用于取消/并发测试
- `artifact-demo.sh` —— 写 `report.txt` 到 `$ARTIFACT_DIR`

## 兼容方案

- 新字段均带默认值，`ALTER TABLE … ADD COLUMN IF NOT EXISTS`。
- 已有 67 个测试全部保留，新增测试并行加入。
- 前端 `script.riskLevel` 未设置时按 `READ_ONLY` 渲染。
- ExecutionRequest 增加 `confirmToken` 可选字段；旧客户端继续可用。
- 旧脚本无 visibleWhenJson 时 ParamForm 不做条件渲染。

## 分阶段开发

- Phase 0：阅读 + baseline（已完成：67 tests）
- Phase 1：风险等级 + 危险确认
- Phase 2：条件参数
- Phase 3：取消任务
- Phase 4：并发 / 防重复
- Phase 5：日志增强
- Phase 6：脚本模板
- Phase 7：执行快照
- Phase 8：Shell 语法检查
- Phase 9：Artifact
- Phase 10：清理机制
- Phase 11：前端 UX 整理
- Phase 12：完整回归 + 最终打包
