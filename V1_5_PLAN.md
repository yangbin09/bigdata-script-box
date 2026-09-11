# BigData Script Box V1.5 Plan

> 本次升级在现有 V1.0 基础上做**增量演进**，不推翻架构。新增 10 个功能，但尽量复用已有代码路径，保持“方便 > 简单 > 稳定 > 功能数量 > 架构高级程度”。

## 一、当前架构（baseline 摘要）

- **后端**：Spring Boot 3.3.5 + MyBatis-Plus 3.5.7 + H2 文件库 + JSR-310 LocalDateTime。
- **前端**：Vue 3 + Vite 5 + Element Plus 2 + Pinia + axios。
- **构建**：单一 fat JAR（`script-box.jar`），运行时不需要 Node。
- **核心模块**：
  - `entity/` Tenant / Script / ScriptParam / ExecutionHistory
  - `mapper/` MyBatis-Plus BaseMapper
  - `service/` ScriptService / TenantService / HistoryService + `executor/ScriptExecutor`
  - `controller/` ScriptController / TenantController / ExecutionController / HistoryController / SystemController
  - `config/` Properties / DataInitializer / WebConfig / InMemoryMultipartFile
  - `dto/` ApiResponse / ExecutionRequest
- **数据库表**：tenant / script / script_param / execution_history（用 `CREATE TABLE IF NOT EXISTS` + `ALTER TABLE ADD COLUMN IF NOT EXISTS` 保证幂等）。
- **目录布局**：
  - `./data/db/scriptbox.mv.db` —— H2
  - `./data/scripts/{id}/script.sh` —— 脚本正文
  - `./data/keytabs/tenant_{id}_*.keytab` —— keytab
  - `./data/executions/{execId}/{stdout,stderr}.log` —— 执行产物
- **执行模型**：ProcessBuilder(List<String>) + 后台 drain 线程 + waitFor(timeout) + destroyForcibly。Mock 模式跳过 kinit 包装；真实模式写一个 `_kinit_wrap.sh` 包一层。
- **前端**：5 个路由 `#/` `#/scripts` `#/scripts/edit` `#/tenants` `#/history`，hash 模式。

## 二、需要修改的表（H2 兼容升级，全部 `ADD COLUMN IF NOT EXISTS`）

### `script`
```
ALTER TABLE script ADD COLUMN IF NOT EXISTS precheck_config_json VARCHAR(4096);
```
（所有 precheck 信息全部塞这个 JSON 字段里，避免再加一张表。）

### `execution_history`
```
ALTER TABLE execution_history ADD COLUMN IF NOT EXISTS batch_id VARCHAR(64);
ALTER TABLE execution_history ADD COLUMN IF NOT EXISTS scenario_id BIGINT;
ALTER TABLE execution_history ADD COLUMN IF NOT EXISTS scenario_step_no INT;
ALTER TABLE execution_history ADD COLUMN IF NOT EXISTS result_json_path VARCHAR(512);
ALTER TABLE execution_history ADD COLUMN IF NOT EXISTS status VARCHAR(32);
```

## 三、需要新增的表

| 表名 | 字段 |
| --- | --- |
| `script_preset` | id, script_id, name, params_json, description, create_time, update_time |
| `script_version` | id, script_id, version_no, script_content, remark, created_at |
| `global_variable` | id, variable_key, variable_value, description, sensitive, enabled, create_time, update_time |
| `scenario` | id, name, description, category, enabled, create_time, update_time |
| `scenario_step` | id, scenario_id, step_no, script_id, preset_id, continue_on_failure, create_time |

## 四、需要新增的类（Java）

```
entity/
  ScriptPreset.java
  ScriptVersion.java
  GlobalVariable.java
  Scenario.java
  ScenarioStep.java
  ExecutionHistory.java          // 新增字段 batchId / scenarioId / scenarioStepNo / resultJsonPath / status

mapper/
  ScriptPresetMapper.java (+ xml 可选)
  ScriptVersionMapper.java
  GlobalVariableMapper.java
  ScenarioMapper.java
  ScenarioStepMapper.java

dto/
  ExecutionRequest.java          // 加 presetId / fileInputs
  BatchExecutionRequest.java
  BatchExecutionResult.java
  ScriptPackageManifest.java
  PrecheckResult.java
  ScenarioExecutionResult.java

service/
  PresetService.java
  ScriptVersionService.java
  ScriptPackageService.java      // 导入 / 导出 ZIP
  GlobalVariableService.java     // 注入到 ProcessBuilder environment
  PrecheckService.java           // 执行前检查
  ScenarioService.java           // 场景定义
  ScenarioExecutionService.java  // 串行执行
  BatchExecutionService.java     // 多租户串行执行
  ResultParserService.java       // result.json 解析
  FileUploadService.java         // 文件参数保存到 executions/{id}/input/

controller/
  PresetController.java
  ScriptVersionController.java
  ScriptPackageController.java
  GlobalVariableController.java
  ScenarioController.java
  PrecheckController.java
  ExecutionController.java       // 增加 /preview /batch /precheck

executor/
  ScriptExecutor.java            // 接入 globalVariableService、precheckService、fileUploadService、resultParserService
```

## 五、需要修改的现有文件

- `entity/ExecutionHistory.java` —— 增加新字段
- `entity/Script.java` —— 增加 `precheckConfigJson`
- `dto/ExecutionRequest.java` —— 增加 `presetId`、`fileInputs`
- `executor/ScriptExecutor.java` —— 注入 GlobalVariableService / PrecheckService / FileUploadService / ResultParserService；支持 DryRun；支持 batch 模式；状态枚举
- `service/ScriptService.java` —— `saveScriptBody` / `create` / `update` 触发 ScriptVersionService.snapshot
- `service/TenantService.java` —— 不变
- `controller/ScriptController.java` —— 不变（保持向后兼容）
- `controller/ExecutionController.java` —— 加 /preview、/batch、/precheck
- `src/main/resources/db/schema.sql` —— 加新表 + 兼容性 ADD COLUMN
- `src/main/resources/application.yml` —— 加 maxInputFileBytes / maxResultJsonBytes

## 六、需要修改的 Vue 页面

| 路由 | 说明 |
| --- | --- |
| `#/scenarios`（新增） | 场景列表 + 编辑 |
| `#/settings`（新增） | 全局变量管理 |
| `#/scripts/edit` | 改为 Tabs：基本信息 / Shell / 参数 / 执行前检查 / 版本历史 / 顶部加 Preset 折叠区 + 导入导出按钮 |
| `#/`（执行中心） | Drawer 增加 Preset 选择 + 文件参数 + 预览按钮 + 批量执行入口 |
| `#/history` | status filter 增加 `precheck_failed` / 列表展示 batchId / 类型列 |
| `AppLayout.vue` | 增加「场景」「设置」两个导航项 |

## 七、API 变化（新增路径，旧的 100% 兼容）

```
POST   /api/scripts/{id}/presets              列表 / 创建
GET    /api/scripts/{id}/presets/{presetId}
PUT    /api/scripts/{id}/presets/{presetId}
DELETE /api/scripts/{id}/presets/{presetId}
POST   /api/scripts/{id}/presets/{presetId}/apply   返回 params 字典（用于前端回填）

GET    /api/scripts/{id}/versions
GET    /api/scripts/{id}/versions/{versionNo}
POST   /api/scripts/{id}/versions/{versionNo}/rollback
GET    /api/scripts/{id}/export             (返回 zip)
POST   /api/scripts/import                  (multipart .zip)

GET    /api/global-variables
POST   /api/global-variables
PUT    /api/global-variables/{id}
DELETE /api/global-variables/{id}

GET    /api/scenarios
POST   /api/scenarios
PUT    /api/scenarios/{id}
DELETE /api/scenarios/{id}
GET    /api/scenarios/{id}/steps
POST   /api/scenarios/{id}/execute

POST   /api/executions/preview             (不真正执行)
POST   /api/executions/batch               (多租户)
POST   /api/scripts/{id}/precheck          (只跑前置检查)
GET    /api/executions/{id}/result         (result.json)
```

## 八、迁移方案（重要）

- **零破坏**：所有新表都是 `CREATE TABLE IF NOT EXISTS`，所有新字段都是 `ALTER TABLE ADD COLUMN IF NOT EXISTS`，老数据不丢。
- **状态字段**：`status VARCHAR(32)` 默认 NULL，老历史 `success=true → SUCCESS`、`success=false && timeout=false → FAILED`、`timeout=true → TIMEOUT`。
- **新增表**：`script_preset` / `script_version` / `global_variable` / `scenario` / `scenario_step` 全部 IF NOT EXISTS，初始为空。
- **测试隔离**：`BaseIntegrationTest` 已经在测试用独立 `./target/test-db/`，升级安全。
- **不需要 Flyway**，用现有 `spring.sql.init.mode=always` 配合 IF NOT EXISTS 即可。

## 九、测试方案

每个 Phase 都要新增一个测试类，最终 12+ 测试类：

1. `PresetServiceTest`
2. `ScriptVersionServiceTest`
3. `ScriptPackageServiceTest`（含 ZIP Slip 防御）
4. `GlobalVariableServiceTest`（含 sensitive 脱敏）
5. `ScenarioExecutionServiceTest`
6. `ResultParserServiceTest`
7. `DryRunControllerTest`
8. `FileParameterServiceTest`
9. `BatchExecutionServiceTest`
10. `PrecheckServiceTest`
11. 升级 `ScriptExecutorTest` 接入新行为
12. 升级 `ApiSmokeTest` 验证新路径

## 十、Phase 顺序

- Phase 0：✅ Baseline 验证
- Phase 1：Preset
- Phase 2：Script Version
- Phase 3：Global Variable
- Phase 4：Dry Run
- Phase 5：result.json
- Phase 6：File Parameter
- Phase 7：PreCheck
- Phase 8：Import / Export
- Phase 9：Batch
- Phase 10：Scenario
- Phase 11：UX 统一（前端导航 / 新页面 / Tabs 化脚本编辑）
- Phase 12：完整回归 + 打包验证

## 十一、安全 / 兼容细节

- 文件参数路径只在 server 端生成，禁止客户端控制路径。
- ZIP 导入：使用 `ZipInputStream` + `getName()` 必须规范化为 `executions/...` 子路径前缀 + 拒绝 `..`。
- result.json 上限 1MB，解析失败仅记日志不影响主流程。
- Keytab 二进制不入库；sensitive 全局变量在日志中以 `******` 输出。
- 全局变量大小写不敏感，但存储保持用户输入大小写。
- 所有新增 Process 仍然使用 `ProcessBuilder(List<String>)`，禁止 `bash -c` 拼接。