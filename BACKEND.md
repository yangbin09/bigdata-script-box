# 后端设计说明

> **本文档的来路与维护方式**：由仓库早期多份后端计划 / 评审 / 重构报告合并、去重后重写而成，并已逐条按当前代码（`src/main/java`、`src/main/resources/application.yml`、`src/main/resources/db/schema.sql`、`src/test/java`）校对。早期报告写于不同时点、彼此结论有冲突，凡与代码不符的陈述都已删除或改写（例如已被删除的 `RunningExecutionRegistry`、未实现的 `RetentionService`、已改名的端点）。
>
> 本文是 `README.md` 的深度补充。README 已覆盖的内容（技术栈、构建与运行命令、目录树、REST 端点表、动态参数类型清单、执行模型概述、安全条目清单、验收清单）此处不再重复。
>
> 不引用行号（会腐烂），只引用类名 / 方法名 / 配置键 / 表名。

---

## 1. 分层与包结构

后端共 102 个 Java 文件，包路径统一在 `com.bigdata.scriptbox` 下。

```
com.bigdata.scriptbox
├── ScriptBoxApplication.java      # 启动类；显式声明不引入 @EnableScheduling
├── config/        (7)             # 配置绑定、Jackson、MDC 过滤器、启动日志、SPA 回退、初始化注入
├── controller/   (15)             # @RestController，只做协议转换
├── dto/           (3)             # ApiResponse / ExecutionRequest / ExecutionPreview
├── entity/       (13)             # @TableName 持久化实体（Lombok @Data）
├── exception/     (3)             # BusinessErrorCode / BusinessException / GlobalExceptionHandler
├── executor/     (10)             # 执行引擎：准备器、命令构造器、进程执行器、上下文与 DTO
├── mapper/       (13)             # MyBatis-Plus BaseMapper
├── model/         (4)             # CleanupResult / ExecutionStatus / RiskLevel / VisibleWhen
├── service/      (23)             # 业务服务
│   └── precheck/  (7)             # PreCheck 策略族（Strategy + Registry）
└── util/          (3)             # FileSystemUtils / MdcContext / TextDecoder
```

### 1.1 各包职责与关键类

| 包 | 职责 | 关键类 |
| --- | --- | --- |
| `config` | 配置绑定与基础设施 | `ScriptBoxProperties`（`@ConfigurationProperties("scriptbox")` + `@Validated`，所有 `scriptbox.*` 的唯一绑定点）；`JacksonConfig`（共享 `ObjectMapper` Bean，注册 `JavaTimeModule`、关闭 `WRITE_DATES_AS_TIMESTAMPS` 与 `FAIL_ON_UNKNOWN_PROPERTIES`）；`RequestMdcFilter`（`OncePerRequestFilter`，`HIGHEST_PRECEDENCE + 1`）；`StartupLogger`（`ApplicationReadyEvent` 打一条生效配置 + 路径）；`WebConfig`（SPA 回退）；`DataInitializer`（`CommandLineRunner` 注入示例数据）；`InMemoryMultipartFile`（内置脚本走正常入库流程的数据源） |
| `controller` | 只做协议转换，不碰持久层 | `ScriptController`、`ExecutionController`、`TenantController`、`HistoryController`、`BatchController`、`ScenarioController`、`PresetController`、`ScriptVersionController`、`ScriptPackageController`、`ScriptTemplateController`、`GlobalVariableController`、`PrecheckController`、`FileUploadController`、`AdminController`、`SystemController` |
| `dto` | 对外 / 跨层契约 | `ApiResponse<T>`；`ExecutionRequest`（执行入参：`scriptId/tenantId/params/presetId/fileInputs/batchId/batchRowIndex/scenarioId/scenarioStepNo/confirmToken/rerunSnapshot/bypassDangerousCheck`）；`ExecutionPreview`（dry-run 类型化响应） |
| `entity` | 与表一一对应 | `Tenant`、`Script`、`ScriptParam`、`ExecutionHistory`、`ScriptPreset`、`ScriptVersion`、`GlobalVariable`、`Scenario`、`ScenarioStep`、`ScriptTemplate`、`ExecutionArtifact`、`SystemSetting`、`CleanupHistory` |
| `exception` | 错误码与统一翻译 | `BusinessErrorCode`（30 个枚举）、`BusinessException`、`GlobalExceptionHandler`（`@RestControllerAdvice`） |
| `executor` | 执行引擎 | `ScriptExecutor`（编排者）、`ExecutionPreparer`（纯规划）、`CommandBuilder`（命令 + 环境）、`ProcessRunner`（唯一生产进程实现，`implements CommandExecutor`）、`ExecutionGate` 在 `service` 包但属于执行引擎的并发准入闸门；`ExecutionContext` / `ProcessRequest` / `ProcessResult` / `CommandSpec` / `CommandResult` / `CommandExecutor` |
| `mapper` | 13 个 `BaseMapper`；6 个自定义方法写在 `resources/mappers/*.xml`（`ExecutionHistoryMapper`、`GlobalVariableMapper`、`ScenarioStepMapper`、`ScriptParamMapper`、`ScriptPresetMapper`、`ScriptVersionMapper`） | — |
| `model` | 无持久化语义的枚举 / 值对象 | `ExecutionStatus`（`RUNNING/SUCCESS/FAILED/TIMEOUT/CANCELLED/PRECHECK_FAILED`，`of()` 把未知值归一为 `FAILED`）；`RiskLevel`（`READ_ONLY/WRITE/DANGEROUS` + `CONFIRM_TOKEN="CONFIRM"`）；`CleanupResult`（`SUCCESS/PARTIAL/FAILED`）；`VisibleWhen`（条件参数规则） |
| `service` | 业务能力 | 见 §5 与 §4 各节；`SensitiveDataMasker`、`StoragePathService`、`ExecutionGate` 是全项目横切组件 |
| `util` | 无状态工具 | `MdcContext`（`AutoCloseable` 的 MDC 批量挂载 / 清理）；`FileSystemUtils`（`directorySize` / `deleteRecursively`，统一不跟随软链接 + 单文件失败只 WARN）；`TextDecoder`（按 BOM 嗅探 + `CodingErrorAction.REPLACE` 的宽松解码，绝不抛异常） |

### 1.2 依赖方向

```
controller ──▶ service ──▶ mapper ──▶ entity
      │            │
      └──▶ executor ┘        model / exception / util 被各方按需引用
```

- Controller **不注入 Mapper、不手写 `QueryWrapper`、不 `new ObjectMapper()`**（`ScriptController` 的 `relatedCounts`、`TenantController`、`ScenarioController` 已下沉为 Service 的 `relatedCounts`，返回键名保持 `historyCount` / `presetCount` / `scenarioCount` 不变）。
- Service 不反向依赖 Controller；`PreviewStore` 有意不依赖 `exception` 包（所以它自带 `PreviewExpiredException`，由 advice 单独映射）。
- `ScriptService.getMapper()` 这一「Service 泄漏持久层」的出口已删除，改为意图方法（如 `savePrecheckConfig(Long, String)`）。

### 1.3 注入约定

- 默认 **`@RequiredArgsConstructor` + `private final`**（Lombok 构造器注入）。业务日志统一用 Lombok `@Slf4j`。
- 例外（手写构造函数）：`SystemController`（需 `@Value("${bigdata.environment-name:Mock 环境}")`）、`ProcessRunner`（两参数显式构造）。
- 残留违背：`FileUploadController`、`ScriptPackageController`、`ScriptTemplateController` 仍是 `@Autowired` 字段注入（§9）。
- `@Transactional` 只出现在 `ScriptService`（`create` / `update` / `replaceParams`）与 `ScriptVersionService`（`snapshot` / `rollback`），包裹「DB 写 + 文件写」。**`ScriptExecutor` 全链路没有事务**——`process.waitFor(timeout)` 绝不在数据库事务内。

---

## 2. 请求处理链路与统一响应

### 2.1 `ApiResponse` 契约

```
ApiResponse<T> { int code; String errorCode; String message; T data; }
```

- `code = 0` 成功；`code = 1` 失败（保留 V1 语义，前端旧分支仍可用）。
- `errorCode` 是 `BusinessErrorCode.name()`，成功时为 `null`。前端可渐进从「解析 message 文案」迁移到「读 errorCode 分流」。
- `data` 承载业务负载；失败时为 `null`（Bean Validation 失败时携带字段错误清单）。
- **所有业务错误返回 HTTP 200**，语义由 `code` / `errorCode` 表达；只有未捕获异常走 HTTP 500（`message` 只给异常类名，堆栈仅落日志）。

### 2.2 异常与校验

`GlobalExceptionHandler` 的映射表：

| 异常 | 结果 |
| --- | --- |
| `BusinessException` | `code=1`，`errorCode=ex.getCode().name()`，WARN 日志 |
| `PreviewStore.PreviewExpiredException` | `errorCode=PREVIEW_EXPIRED` |
| `IllegalArgumentException` / `IllegalStateException` | 降级为 `INTERNAL_ERROR`（迁移期兜底，保留 message） |
| `MethodArgumentNotValidException` | `errorCode=PARAMETER_INVALID` + 字段错误清单 |
| 其它 `Exception` | HTTP 500 + `"服务器内部错误: <类名>"`，ERROR 日志带完整堆栈 |

约定：**业务校验失败一律抛 `BusinessException(code, message)`**，Controller 不写 try/catch。参数类型 / 必填 / 枚举 / 风险等级的校验在 Service 与 `ExecutionPreparer` 内完成，不依赖 Bean Validation（`spring-boot-starter-validation` 已引入，但 `ExecutionRequest` 未标注 `@Valid`）。

`BusinessErrorCode`（30 个）当前有 23 个真实抛出点，散落在 `StoragePathService`、`ExecutionPreparer`、`ExecutionGate`、`ScriptService`、`ScriptVersionService`、`ScriptPackageService`、`FileUploadService`、`GlobalVariableService`、`ScenarioService`、`TenantService`、`CleanupService`、`RiskLevel`、`GlobalExceptionHandler`。**尚未被使用的 7 个**：`PRESET_NOT_FOUND`、`EXECUTION_NOT_FOUND`、`ARTIFACT_NOT_FOUND`、`TEMPLATE_NOT_FOUND`、`PARAMETER_REQUIRED`、`PRECHECK_FAILED`、`INTERNAL_NOT_IMPLEMENTED`。

### 2.3 MDC 日志上下文

`MdcContext.of(String... pairs)` 实现 `AutoCloseable`，`close()` 时逐个 `MDC.remove`，必须 try-with-resources 使用。两个挂载点：

| 挂载点 | 注入的 key | 来源 | 清理 |
| --- | --- | --- | --- |
| `RequestMdcFilter` | `requestUri`、`executionId`、`batchId`、`scenarioId` | `req.getRequestURI()` + `req.getParameter(...)`（**仅 query parameter**） | `try (MdcContext ...)` |
| `ScriptExecutor.buildMdcContext` | `executionId`、`scriptName`、`tenantName`、`batchId`、`scenarioId` | `ExecutionContext` / `ExecutionRequest` | 同上，包住 precheck → snapshot → 进程 → 收尾整段 |

MDC 只放标识符，**不放任何参数值或敏感值**。执行线程内的日志因此天然带 `executionId`；HTTP 层只有 query 传参（如 `?executionId=`）时才会带上（§9）。

---

## 3. 数据模型与持久化

### 3.1 表清单（以 `db/schema.sql` 为准）

| 表 | 说明 |
| --- | --- |
| `tenant` | 租户：`name` / `principal` / `keytab_path` / `enabled` / `default_database` |
| `script` | 脚本元数据：`script_path` / `timeout_seconds`（DEFAULT 600）/ `enabled` / `favorite` / `default_tenant_id` / `precheck_config_json` / `risk_level` / `allow_concurrent` |
| `script_param` | 动态参数定义：`name` / `type` / `default_value` / `options` / `required` / `sort_order` / `placeholder` / `help_text` / `visible_when_json` |
| `execution_history` | 执行历史，主键由应用生成（非 `AUTO_INCREMENT`）；含 `status` / `success` / `timeout` / `exit_code` / `duration_ms` / `stdout_path` / `stderr_path` / `execution_dir` / `result_json_path` / `batch_id` / `batch_row_index` / `scenario_id` / `scenario_step_no` / `script_sha256` / `snapshot_json` / `result_json` / `summary` |
| `script_preset` | 参数方案：`script_id` / `name` / `params_json` |
| `script_version` | 脚本版本：`script_id` / `version_no` / `script_content` / `remark` / `created_at` |
| `global_variable` | 全局变量：`variable_key` / `variable_value` / `sensitive` / `enabled` |
| `scenario` / `scenario_step` | 场景与步骤：`step_no` / `script_id` / `preset_id` / `continue_on_failure` |
| `script_template` | 内置模板：`code`（稳定 handle）/ `content` / `params_json` / `sort_order` / `enabled` |
| `execution_artifact` | 产物注册表：`execution_id` / `name`（固定 `artifacts/<file>`）/ `path` / `size_bytes` / `sha256` / `mime_type` |
| `system_setting` | 运行时键值配置（`setting_key` 主键） |
| `cleanup_history` | 每次手动清理的审计行：`preview_id` / `retention_json` / `result` / 各类 deleted 计数 / `bytes_freed` / `skipped_count` / `failed_count` / `message` |
| `preset_variable`、`file_upload` | **历史残留**：DDL 仍会创建，但已无实体、Mapper 或调用方（§9） |

DDL 全部 `CREATE TABLE IF NOT EXISTS` + `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`，**不使用 Flyway**，靠 `spring.sql.init.mode=always` 实现幂等升级；`src/test` 用 `./target/test-db/scriptbox` 与 `./target/test-data` 隔离。

### 3.2 关键字段语义

- `execution_history.id` 由应用生成（`ScriptExecutor.nextExecutionId()` = `AtomicLong(System.currentTimeMillis() * 1000)` 起自增），因此 `data/executions/<id>/` 的目录名可以直接解析回 ID——`CleanupService` 的候选构造依赖这个约定。
- `status` 是规范化状态（`ExecutionStatus`），`success` / `timeout` 是 V1 遗留字段，二者语义重叠但不等价：`success` 只在「exitCode==0 且未超时且未取消」时为真。
- `script_sha256` 用于前端提示「脚本在执行后被改过」；`snapshot_json` 存执行时刻的参数 + 脚本正文 + 租户 + 风险开关 + **已脱敏**的全局变量，供「按原样重跑」。
- `execution_artifact.name` 恒为 `artifacts/<filename>`；`path` 是绝对路径，只在服务端做安全校验用，**不返回给前端**。
- 脚本正文落盘路径恒为 `<scriptsRoot>/<scriptId>/script.sh`；keytab 落盘路径为 `<keytabsRoot>/tenant_<id>_<uuid>.keytab`，**二进制内容永不入库**。

### 3.3 MyBatis-Plus 用法

- 实体用 `@TableName` + Lombok `@Data`，主键 `@TableId(type = IdType.AUTO)`（`ExecutionHistory` 例外：主键由应用赋值）。
- `map-underscore-to-camel-case: true`，`id-type: AUTO`，SQL 日志实现为 `NoLoggingImpl`（避免日志噪音）。
- 条件查询目前仍以**字符串列名的 `QueryWrapper`** 为主（如 `CleanupService`、`ArtifactService`、`HistoryService`、`GlobalVariableService`），`LambdaQueryWrapper` 迁移未做。
- 分页靠 `QueryWrapper.last("LIMIT n")`，limit 统一在 Service 内夹取上界（`HistoryService` 夹到 500，`CleanupService.recentHistory` 夹到 200），不使用分页插件。

### 3.4 初始化注入（`DataInitializer`）

启动时 `CommandLineRunner` 依次执行，全部**幂等**（仅在对应表为空时写）：

1. `copyBundledMockScripts()` —— 目前是空实现（占位），示例脚本改为在 register 阶段通过 `InMemoryMultipartFile` 走正常入库流程。
2. 租户表为空 → 插入内置 `mock-hive` 租户（`principal=hive@EXAMPLE.COM`，无 keytab）。
3. 脚本表为空 → 从 `mock-scripts/` 注册 5 个示例脚本：`success` / `failed` / `stderr-mix` / `timeout`（3 秒超时）/ `large-output`。
4. 模板表为空 → 注入 5 个内置模板：`plain-shell` / `kerberos` / `spark-sql` / `hdfs` / `yarn`，各带 `params_json`。

`mock-scripts/` 目录下另有 `file-input.sh` 与 3 个 `result-json-*.sh`，由测试直接引用，不在启动注入清单里。

---

## 4. 执行引擎

### 4.1 总流程（`ScriptExecutor`）

```
execute(req)
  ├─ scriptService.getById(scriptId)                # 不存在 → IllegalArgumentException
  └─ executeWithScript(req, script, bodyOverride)
       executionId = nextExecutionId()              # 全程只生成一次
       ctx = ExecutionPreparer.prepare(...)         # 纯规划 + 受控路径副作用
       try (MdcContext) {
         runPrecheckOrRecordFailure(ctx)            # 失败 → 写 PRECHECK_FAILED 行并 return
         captureSnapshot(ctx)                       # SHA-256 + snapshotJson + 写 RUNNING 行
         startProcess(ctx)                          # 准入 → ProcessRunner → 释放槽位
         finalizeExecution(ctx, result)             # 状态 → result.json → artifact → upsert
       } finally { 删 kinit wrapper；清 pending 上传副本 }
```

`ExecutionPreparer.prepare` 负责：校验 `script.enabled` → DANGEROUS 二次确认 → 加载并校验租户（存在 + enabled）→ 解析参数（回调 `ScriptExecutor::resolveParams`）→ 建 `execDir` 与 `artifacts/` → promote 文件参数（**只一次**）→ 按需生成 0700 kinit wrapper → 组装不可变 `ExecutionContext`。

**ID 生命周期不变量**：`executionId` 在 `executeWithScript` 入口生成一次，绝不用「临时 ID」试探磁盘副作用。历史上这里生成两次，导致每次带文件参数的执行都会留下一个永不被清理的孤儿目录与一份重复文件副本。

### 4.2 参数校验与默认值（`resolveParams` / `validateAndCoerce`）

```
declared = scriptService.paramsOf(scriptId)
supplied = req.params or {}
若 req.presetId != null: merged = preset.applyParams(preset) + supplied   # preset < supplied
否则: merged = supplied
→ validateAndCoerce(declared, merged)
```

`validateAndCoerce` 逐条按 `ScriptParam` 声明处理：

- 空值且有 `defaultValue` → 用默认值；
- `required` 且为空 → `IllegalArgumentException("参数 'x' 必填")`；**`boolean` 例外**（false 也算有值，不视为缺失）；
- `number` → `Double.parseDouble` 校验；`select` → 值必须命中 `options` 逗号分隔白名单；`boolean` → 归一为 `"true"`/`"false"`（空变 `"false"`）；`date` / `text` / `textarea` → 原样保留；
- 声明外的额外参数**静默忽略**，不报错；
- 每条参数按 `VisibleWhen` 规则过滤，不满足则不写入结果 map（即**不传给 shell**）。

`VisibleWhen` 的 JSON 形如 `{"param":"env","operator":"equals","value":"prod"}`，支持 `equals` / `notEquals`，非法或未知 operator 视为「始终可见」。

### 4.3 命令构造（`CommandBuilder`）

- `build(ctx)` → `[<shellExecutable>, <scriptPath 或 wrapperPath>, --k1 v1, --k2 v2, ...]`
- `buildForPreview(ctx)` → 同样的形态，但 kinit wrapper 尚未生成，用占位符 `<executions-dir>/<exec-id>/kinit_wrap_*.sh` 代替。
- `buildEnv(ctx)` → 全局变量（`GlobalVariableService.envForExecution()`）+ `EXECUTION_ID` / `EXECUTION_DIR` / `ARTIFACT_DIR`（均为绝对路径）+ `LANG=C.UTF-8` / `LC_ALL=C.UTF-8` 兜底（`putIfAbsent`，脚本可在全局变量里覆盖）。
- `buildArgsFromMap(params)` → 摊平成 `--key value` 列表。
- **全部命令都以 `List<String>` 提交给 `ProcessBuilder`，生产代码中不存在 `bash -c` 拼接**（`bash -c` 只出现在测试里，用于构造固定退出码 / 长时间运行的样本进程）；shell 可执行名来自 `scriptbox.shell-executable`，代码内没有任何硬编码 `"bash"`（`CommandExistsPrecheckStrategy` 还会容忍 `.exe` 后缀并按该配置解析，避免「precheck 说没装 bash 而脚本其实能跑」）。

### 4.4 kinit 包装

仅当 `!mock && tenant.keytabPath` 非空时启用。`ExecutionPreparer.createKinitWrapper`：

- 用 `Files.createTempFile(execDir, "kinit_wrap_", ".sh")`（文件名随机，避免并发同名）；
- `Files.setPosixFilePermissions` 设为 `rwx------`（0700），非 POSIX 文件系统捕获 `UnsupportedOperationException` 跳过；
- 内容：`#!<shellExecutable>` + `set -e` + `<kinitExecutable> -kt '<keytab>' '<principal>' || exit 127` + `exec "<scriptPath>" "$@"`，路径与 principal 都经 `shellQuote`（单引号包裹 + 转义单引号）；
- 路径写入 `ExecutionContext.wrapperPath`，由 `finalizeExecution` 的 `finally` **无条件删除**（成功 / 失败 / 超时 / 取消 / 异常都删），防止 0700 文件长期残留在执行目录里暴露 keytab 路径。

`<kinitExecutable>` 只用于租户连通性测试与 wrapper；`TenantService.testConnectivity` 走 `CommandExecutor` 且 `streamOutput=true`（只看退出码，不落盘）。

### 4.5 进程启动、超时与输出（`ProcessRunner`）

`ProcessRunner` 是 `CommandExecutor` 的唯一生产实现，两种入口：

| 入口 | 用途 | 特点 |
| --- | --- | --- |
| `run(executionId, ProcessRequest)` | 用户脚本执行 | 输出落磁盘文件、绑定 `ExecutionGate` 许可（可取消）、`props.getMaxOutputBytes()` 预算 |
| `exec(CommandSpec)` | 内部短命令（`bash -n`、`kinit`、`klist`） | 不占并发槽位、独立硬超时（`command-timeout-seconds`）、`streamOutput=true` 时完全不设重定向 |

关键设计（改动这块前必须理解）：

1. **输出只用内核重定向**：`pb.redirectOutput(File)` / `pb.redirectError(File)`，`redirectErrorStream(false)`。不用应用侧读管道的原因：管道必须有线程持续消费否则进程写满缓冲就阻塞；进程被强杀后孙进程仍持有管道写端会导致读取线程永远等不到 EOF；且在另一个线程 `close()` 一个正被 `read()` 的流并不生效。内核重定向同时保证**输出逐字节忠实**（不再有 `readLine()+newLine()` 把 `\r\n` 改写成 `\n` 的问题）。
2. **输出上限是「事后截断」**：进程结束后 `truncateToBudget` 把超限文件截到 `max-output-bytes`，保证死循环输出不会把磁盘打满。
3. **超时判定不能只看 `waitFor` 的布尔返回值**：返回 `false` 也可能是「进程已退出但 onExit 通知未送达」。正确做法是 `waitFor(timeout, SECONDS)` 返回 false 后再问一次 `process.isAlive()`，只有确实还活着才算 `timedOut`。旧实现把 `false` 直接当超时，会把退出码 7 改写成哨兵 `-1` 并误报超时。
4. **终结后验证**：`destroyTree` 之后 `awaitQuietly(process, EXIT_WAIT_SECONDS=30s)` 等真正退出（内核才会刷盘并释放句柄）；仍未退出则记 ERROR 并置位 `lingering`。`finally` 里再做一次 `isAlive()` 兜底销毁。
5. **句柄释放探测**：`awaitHandlesReleased` 对输出文件做有界轮询（上限 3s），避免「后代稍晚才死 → 目录删不掉 → 清理偶发失败」。探测方式是 `FileChannel.open(WRITE)`，**这是启发式而非可靠判定**（§9）。
6. 超时 / 取消路径统一把 `exitCode` 置 `-1`，调用方靠 `timeout` / `cancelled` 字段区分原因；`ok() = exitCode == 0 && !timeout && !cancelled`。

`ProcessRunner` 的 `run` 在 `finally` 里只做 `gate.releaseProcess(executionId)`（解绑进程句柄），**槽位由调用方持有的 `Permit` 释放**——因此槽位覆盖到「进程结束 + result.json 解析 + artifact 扫描 + 落库」全段结束。

### 4.6 执行目录布局

```
<executionsDir>/<executionId>/
├── stdout.log            # 内核重定向写入（截断到 max-output-bytes）
├── stderr.log            # 内核重定向写入
├── result.json           # 脚本可选输出，解析后写入 history.result_json
├── artifacts/            # $ARTIFACT_DIR；脚本唯一可写工作目录，扫描不递归
├── script.sh             # 仅 snapshot rerun 时落盘（普通执行直接用 scriptsRoot 下的原文件）
└── kinit_wrap_*.sh       # 临时 wrapper，执行结束即删
```

`<dataRoot>/uploads/<token>/` 是文件参数的两段式暂存区，**不在** `CleanupService` 的管辖范围内，因此在 `finalizeExecution` 的 `finally` 里主动回收（`FileUploadService.cleanupPending`）。`<execId>/input/` 则**刻意不删**：它属于 `executionsRoot`，由 Cleanup 按保留天数统一回收，且保留下来运维才能复核「这次执行喂了哪些输入文件」。

### 4.7 状态判定（`finalizeExecution`）

优先级：**取消 > 超时 > 成功/失败**。

| 条件 | `status` | `success` |
| --- | --- | --- |
| `cancelled` | `CANCELLED` | `true`（`ok()` 为 false） |
| `timedOut` | `TIMEOUT` | false |
| `exitCode == 0` | `SUCCESS` | true |
| 其它 | `FAILED` | false |
| PreCheck 失败（未起进程） | `PRECHECK_FAILED`，`exitCode=-1`，`durationMs=0` | false |

收尾阶段三件事都是「尽力而为、失败不拖垮主流程」：解析 `result.json`（缺失 / 超 1MB / 格式错都只 WARN）、扫描 artifact 并注册、`upsertHistory`。`drainFailed` 为真时额外打一行 WARN（stdout/stderr 被认为不完整）。

`ExecutionStatus.of()` 把历史脏值归一为 `FAILED`，保证前端不会看到 null。

### 4.8 快照与重放

- `captureSnapshot` 读脚本正文算 SHA-256，并构建 `snapshotJson`：`scriptId/scriptName/displayName/description/riskLevel/allowConcurrent/timeoutSeconds/tenantId/tenantName/params/body/globalVariables`。**keytab 路径不写入**，敏感全局变量值经 `SensitiveDataMasker.maskEnv` 遮罩。同一 `executionId` 只 upsert 一次 RUNNING 行（`upsertHistory` 是唯一的 insert/update 判定点）。
- `rerunFromSnapshot(executionId)`：从 `snapshot_json` 取 `scriptId` / `params` / `body`，校验脚本仍存在，然后以 `scriptBodyOverride = body` + `bypassDangerousCheck = true` + `rerunSnapshot = true` 走一次**完整**执行流程（含 PreCheck、快照、准入、artifact）。
- 重放**不再改写原始脚本文件**，也不再有「预生成 ID + 临时副本 + finally 删除」那套流程：`materializeScript` 把快照正文写到**本次执行自己的** `executionDir/script.sh`（幂等，已存在则不重写），随 Cleanup 一起回收；`captureSnapshot` 对 `rerunSnapshot=true` 跳过 `scriptsRoot` 前缀校验。
- 重放仍使用**当前的** Script 实体（风险等级、超时、enabled、precheck 配置取现网值），只有参数与正文来自快照。

### 4.9 并发准入（`ExecutionGate`）

`ExecutionGate` 是并发槽位、同脚本去重、取消状态的**唯一权威**：

- `acquire(executionId, scriptId, tenantId, scriptName, allowConcurrent)` 在**单个 `synchronized` 临界区**内一次性完成「同脚本冲突检查 + 槽位余量检查 + 占位登记」，竞态窗口为零。历史上这里是 check-then-act，窗口长到足以让 N 个并发请求同时通过检查、静默击穿 `max-concurrent`。
- `allowConcurrent=false`（`script.allow_concurrent` 默认 false）时，同 `scriptId` 已有未结束许可 → 拒绝（`EXECUTION_ALREADY_RUNNING`）；占用数达到 `scriptbox.max-concurrent` → 拒绝（`EXECUTION_SLOT_LIMIT_REACHED`）。
- `Permit implements AutoCloseable`：`close()` 从 map 移除、置 `finished`、状态转 `FINISHED`、`occupied.decrementAndGet()`。**槽位从准入一直持有到整段执行收尾结束**（不是进程退出就放行）。
- `State` 状态机：`RESERVED → RUNNING → CANCELLING → FINISHED`。`Permit.bindProcess(Process)` 在 `pb.start()` 之后绑定句柄；若绑定之前就已被取消，会立刻 `destroyTree`，取消请求不丢。
- 内部短命令（`bash -n` / `kinit` / `klist`）**不经过**闸门，不挤占用户脚本名额。
- `destroyTree(process, waitForDescendants)`：先 `descendants().destroyForcibly()` 再 `destroyForcibly()` 父进程；`waitForDescendants=true` 时短轮询（上限 `DESCENDANT_WAIT_MS = 1500ms`）等子孙真正消失——孙进程存活会持续持有重定向句柄并可能变成孤儿。`cancel` 与 `ProcessRunner` 共用这一份实现。

### 4.10 取消

- `POST /api/executions/{id}/cancel` → `ExecutionGate.cancel(id)`：临界区内置 `cancelled` + `revoked`、状态转 `CANCELLING`、`destroyTree`。**幂等**：已结束 / 已取消返回 false。
- `GET /api/executions/{id}/state` 与 `GET /api/executions/active` 都以 `ExecutionGate` 的许可为「运行中」的权威来源，再回落到 `execution_history.status`。
- `revoked` 集合让 `ProcessRunner` 能区分「许可从来不存在」（正常启动）与「许可已被取消并回收」（必须空转，否则会留下一个没人能取消的进程）。
- 因为 `POST /api/executions` 是阻塞到结束的，前端拿不到 executionId，所以先查 `/active` 再调 `/cancel`。

### 4.11 批量（`BatchService`）

`POST /api/batches/execute` 用同一脚本对多组参数各跑一次，共享一个 `batchId`（`batch-<counter>`），每行一条 `ExecutionHistory` 并带 `batch_row_index`。

- `runSequential`：逐行执行，单行异常只记 WARN 并计入 `failed`，不影响后续行。
- `runParallel`：当 `concurrency > 1` 时用固定线程池（daemon 线程），并发上限夹到 `scriptbox.max-batch-concurrency`（默认 8）；等待阶段区分 `InterruptedException`（恢复中断标志）与其它异常（ERROR + 堆栈），并在 `finally` 里 `shutdownNow()`。
- `validateRows` 统一校验非空 + 不超过 `scriptbox.max-batch-rows`（默认 200），顺序 / 并行两条路径共用（历史上这两处各写了一遍）。
- `BatchSummary` 是 record，含 `batchId/total/succeeded/failed/historyIds`；`Builder` 的累加方法用 `synchronized` 保护（并行汇总）。**不含失败原因明细**（§9）。

### 4.12 场景（`ScenarioService`）

`POST /api/scenarios/{id}/run?tenantId=` 按 `step_no` 升序逐步骤执行，每步一条 history 并带 `scenarioId` / `scenarioStepNo`：

- 步骤可绑定 `presetId`（`ScenarioStep.preset_id`）；
- 首次失败即中止（fail-fast），除非该步骤 `continueOnFailure=true`；
- 步骤抛异常时该步记 `status="ERROR"` + `error` message，同样受 `continueOnFailure` 控制；
- 返回 `RunResult{scenarioId,total,succeeded,failed,aborted,abortedAtStep,historyIds,entries}`，`RunEntry` 提供 `toMap()`。
- 场景 `disabled` 或没有任何步骤 → `SCENARIO_DISABLED` / `SCENARIO_NOT_FOUND`。

---

## 5. 可靠性与运维

### 5.1 产物（`ArtifactService`）

`finalizeExecution` 结束时扫描 `<execId>/artifacts/` 并注册到 `execution_artifact`：

- 扫描**不递归**（只 `Files.list` 一层，`Files.isRegularFile`），按文件名排序；
- 单文件超过 `max-artifact-bytes` → WARN 跳过；注册数达到 `max-artifact-files` → 停止；
- 扫描前先删除该 execution 的旧行（重跑不会累积脏数据），落 `name` / 绝对 `path` / `size_bytes` / `sha256` / `mime_type`；
- 越限只告警，**不影响本次执行的成功状态**。
- 下载：`resolveSafe(executionId, identifier)` 支持 artifact 主键或相对名（`report.csv` 或 `artifacts/report.csv`）。`normaliseName` 拒绝 `..`、绝对路径前缀、Windows 盘符与路径分隔符；`resolveSafe` 再把 on-disk 绝对路径 normalize 后校验必须落在该执行的 `artifacts/` 目录内，并要求是常规文件。
- 前端视图 `listView` 只暴露 `id/name/sizeBytes/sha256/mimeType/createdAt`，**不暴露绝对路径**；下载响应头文件名经 `safeFilename` 剥离目录与 CRLF / 引号。

### 5.2 `result.json`（`ResultParserService`）

约定格式 `{"status":"SUCCESS|FAILED|WARNING","message":"...","data":{...}}`。`parse` 在收尾阶段调用：

- 文件不存在 / 空 / 超 `MAX_BYTES`（1 MB 硬编码）/ JSON 非法 → 只 WARN，绝不抛出；
- 只有脚本显式声明 `SUCCESS` 才覆盖执行器写入的 status（避免把 timeout / cancelled 降级成 WARNING）；
- 原始 JSON 串存入 `history.result_json`；`readStructured` 在文件缺失时返回 `null`，Controller 把这种情况表达为 `code=0, data=null`（前端渲染「未生成 result.json」占位，而不是错误 toast）。

### 5.3 手动清理（`CleanupService` + `CleanupExecutor` + `PreviewStore`）

**完全手动，没有 cron、没有 `@EnableScheduling`、没有 `@Scheduled`、没有启动触发。** 三段式：

1. `POST /api/admin/cleanup/preview` → `CleanupService.preview(h,a,e,l)`。**纯读**扫描，生成快照并存入 `PreviewStore`，返回 `previewId`。四类候选：
   - **执行目录**：按目录 `lastModifiedTime` 超过 `executionDays`；跳过非目录与软链接；
   - **历史表行**：`start_time` 超过 `historyDays`；
   - **孤儿产物**：所属执行已过期的 `execution_artifact` 行，但**跳过已被执行目录候选覆盖的行**（避免双重删除计数）；
   - **应用日志**：`logsRoot` 存在且 `logDays > 0` 时扫描常规文件。
   运行中的执行（`ExecutionGate.get(id) != null`）标记 `SKIPPED_RUNNING` 并计入 `skippedRunning`。
2. `POST /api/admin/cleanup/execute` → 请求体必须带 `previewId` + 字面量 `confirmToken="CLEAN"`。`AdminController` 先做前置校验，`CleanupService.execute` 再校验一次并抛 `CONFIRM_TOKEN_MISMATCH`，随后 `PreviewStore.get`（过期 / 不存在 → `PREVIEW_EXPIRED`）。
3. `CleanupExecutor.execute(preview)` 分四步（执行目录 → 孤儿产物 → 应用日志 → 历史行）逐项应用，单条失败捕获后继续，最后 `computeResult()` 得出 `SUCCESS` / `PARTIAL`，并由 `CleanupService` 写一行 `cleanup_history` 审计（写审计失败只 WARN）。

安全防护：`deleteExecutionDir` / `deleteFileSafely` 都先 `isSymbolicLink` 直接拒绝，再 `toAbsolutePath().normalize()`，再 `toRealPath(NOFOLLOW_LINKS)` 后与受控根做 `isInsideReal` 前缀比较，**返回 `-1` 表示未通过检查（跳过而非抛异常）**；删除执行目录前还会用目录名解析出 ID 并二次确认不在运行中（防止 preview 之后被启动）。递归删除与大小统计委托 `FileSystemUtils`（`FileVisitor` post-order，叶子先删；`directorySize` 不跟随软链接、单文件失败只 WARN 不把总数归零）。

`PreviewStore` 是**进程内 `ConcurrentHashMap`**：UUID 作 key，`PREVIEW_TTL_MS = 10 分钟` 过期，`get` 顺手 evict 过期条目，执行成功后主动 evict；进程重启即清空（未确认的预览消失，让用户重新点一次「预览」即可）。

保留天数的优先级是 **`system_setting` 覆盖 > `application.yml` > 代码默认值**，`0` 表示禁用该类别；日志类别还要求 `logsRoot` 目录真实存在，否则 `logsEnabled=false` 并写入一条 warning。

### 5.4 运行时设置与系统信息

- `GET/PUT /api/admin/settings` 读写 `system_setting`。写入**白名单**，只放行 `cleanup.historyDays` / `cleanup.artifactDays` / `cleanup.executionDays` / `cleanup.logDays`（常量定义在 `CleanupService.K_*`），未知键直接拒绝。
- `GET /api/admin/cleanup/history?limit=20` 返回最近清理审计（limit 夹在 `[1, 200]`）。
- `GET /api/system/info` 返回 `{mock, environmentName}`，前端用它确认后端存活并显示环境标签。
- `StartupLogger` 在 `ApplicationReadyEvent` 打一条生效配置（模式 / 并发上限 / 四类保留天数）+ 四条关键路径，避免「改了配置没生效」。

### 5.5 日志保留

`logback-spring.xml`：`LOG_DIR` 取自 `scriptbox.logs-dir`（默认 `./logs`），`TimeBasedRollingPolicy` 按天滚动到 `${LOG_DIR}/scriptbox.%d{yyyy-MM-dd}.log.gz`，`maxHistory=3`、`totalSizeCap=1GB`、`cleanHistoryOnStart=false`，同时保留 Console appender。

| 类别 | 保留策略 |
| --- | --- |
| 应用日志（`scriptbox.log`） | Logback 滚动 **3 天**；`retention-log-days`（默认 3）另行控制手动清理 |
| `stdout.log` / `stderr.log` / `artifacts/` / 执行目录 | **不受 3 天约束**，跟随 `retention-execution-days`（默认 30），只手工清理 |
| `execution_history` 行 | `retention-history-days`（默认 30），`system_setting` 可热更新 |

---

## 6. 安全边界

| 面 | 措施 |
| --- | --- |
| 命令注入 | 全部子进程走 `ProcessBuilder(List<String>)`；生产代码无 `bash -c` 拼接、无字符串命令拼接。shell 可执行名可配（`scriptbox.shell-executable`）。 |
| 路径生成 | 受控根与单对象路径全部由 `StoragePathService` 生成（`scriptsRoot` / `keytabsRoot` / `executionsRoot` / `logsRoot` / `dataRoot`、`scriptFilePath` / `keytabPath` / `executionDirFor` / `artifactsDirFor` / `inputDirFor` / `pendingUploadDir`）。 |
| 路径越界（语法层） | `StoragePathService.assertInside(child, root, what)`：`toAbsolutePath().normalize()` 后前缀比较，越界抛 `BusinessException(PATH_ESCAPE)`。用于执行前脚本路径校验、pending 上传目录、input 目标路径。 |
| 路径越界（真实路径层） | `StoragePathService.isInsideReal`：`toRealPath(NOFOLLOW_LINKS)` 后比较，用于清理 / 删除 / 产物下载；路径不存在返回 false。 |
| 生成路径种类 | 新增任何受控目录都必须走 `StoragePathService`（这是评审明确要求只扩展、不改写的模块）。 |
| 扩展名白名单 | 脚本只接受 `.sh`（`ScriptService.readScriptFile`），keytab 只接受 `.keytab`（`TenantService.saveKeytab`）；参数文件类型为 `file` 时走两段式上传。 |
| 大小上限 | 脚本 `max-script-bytes`（1 MB）；日志单次返回 `max-log-bytes`（1 MB）；参数文件 `max-input-file-bytes`（10 MB）；产物单文件 `max-artifact-bytes`（50 MB）/ 数量 `max-artifact-files`（50）；`result.json` 与 ZIP 单 entry 各 1 MB；HTTP multipart `10MB / 12MB`。 |
| 路径穿越 | 产物 `normaliseName` 拒绝 `..` / 绝对路径 / 盘符 / 分隔符；`resolveSafe` 再校验 on-disk 落在 `artifacts/` 内。ZIP 导入逐 entry 拒绝 `..`、`/` 前缀、`\`，并再 `normalize` 一次（Zip Slip 防御），且只解到内存 map，从不落盘到解压目录。文件上传先拒绝含 `..` / `/` / `\` 的原始名，再用 `[^A-Za-z0-9._-] → _` 清洗并截断到 96 字符。 |
| 客户端不能指定路径 | 日志 / 产物的读取与下载只按 `executionId` + 主键 / 相对名查表；文件参数必须来自 `/api/uploads` 的 pending 根（`srcPath.startsWith(pendingUploadsRoot())`），否则 `PATH_ESCAPE`。清理接口只接受保留天数 + `previewId`，受控根由服务端从配置派生。 |
| 软链接 | 清理侧：候选路径是软链接一律跳过；删除前 `isInsideReal` 二次校验。统计侧：`FileSystemUtils` 不跟随软链接（防自引用环）。 |
| 取消隔离 | 取消按 `executionId` 精确绑定许可与进程，不会误杀其它执行。 |
| 敏感值脱敏 | `SensitiveDataMasker` 三件套：`maskEnv`（按 `GlobalVariable.sensitive=true` 遮值，只遮值不遮 key）、`maskCommand`（正则匹配 `--xxx value`，命中 `password/passwd/token/secret/credential/keytab` 的值替换为 `******`）、`maskCommandList`（`ProcessBuilder` 列表形态）。执行启动日志、snapshot、dry-run 全部走脱敏路径。 |
| 日志不打印参数值 | 日志只打印脚本名 / 租户名 / 参数**键名** / 脱敏后的命令；MDC 只放标识符；keytab 二进制自始至终不进日志、不进 API 响应、不进数据库，只落盘 + 0600/0700 权限（wrapper）。 |
| API 响应脱敏 | 全局变量列表与详情把敏感值渲染为 `******`；dry-run 把 `keytabPath` 替换为 `SensitiveDataMasker.MASK`；产物视图不含绝对路径。 |
| 反序列化 | 所有 `mapper.readValue` 都带 `TypeReference` / 目标类型，未启用 default typing。 |
| 哈希 | SHA-256 用于脚本正文与产物指纹（非安全用途）。 |
| 项目边界 | 无 outbound HTTP（无 SSRF 面）、无 SQL 字符串拼接（全部 Wrapper）、无硬编码 secret、无 `printStackTrace`、无 `System.out.println`（Console appender 除外）。 |

---

## 7. 配置项

`application.yml` 中 `scriptbox.*` 的全部键（默认值即下列值）：

```yaml
scriptbox:
  mock: true                       # true=跳过 kinit；租户连通性测试返回模拟输出
  data-dir: ./data                 # 数据根（uploads/ 在其下）
  scripts-dir: ./data/scripts      # 脚本正文：<id>/script.sh
  keytabs-dir: ./data/keytabs      # keytab：tenant_<id>_<uuid>.keytab
  executions-dir: ./data/executions
  logs-dir: ./logs                 # 应用日志根；目录不存在时日志清理类别自动禁用
  shell-executable: bash           # 所有用户脚本与 bash -n 使用的 shell
  kinit-executable: kinit          # 租户连通性测试 + kinit wrapper
  klist-executable: klist
  command-timeout-seconds: 30      # 内部短命令硬超时（不占并发槽位）
  default-timeout-seconds: 600     # 脚本未声明 timeout_seconds 时的兜底（与 schema DEFAULT 同步）
  max-concurrent: 5                # ExecutionGate 全局槽位上限
  max-output-bytes: 268435456      # 单次执行 stdout / stderr 落盘上限（0=不限）
  max-log-bytes: 1048576           # 单次日志返回给前端的字节上限
  max-script-bytes: 1048576        # 脚本文件上传上限
  max-input-file-bytes: 10485760   # 文件参数上传上限
  max-artifact-bytes: 52428800     # 单产物文件上限
  max-artifact-files: 50           # 单次执行产物文件数上限
  max-batch-rows: 200              # 批量执行行数上限
  max-batch-concurrency: 8         # 批量并行上限
  retention-history-days: 30       # 0=禁用该类清理
  retention-artifact-days: 30
  retention-execution-days: 30
  retention-log-days: 3            # 与 Logback maxHistory=3 对齐
```

绑定与校验：`ScriptBoxProperties` 用 `@ConfigurationProperties(prefix="scriptbox")` + `@Validated` + `@Min` 做启动期 fail-fast；`setMaxOutputBytes` 允许 0（表示不限），其余 setter 对负值做钳制（`Math.max`）。

`scriptbox.*` 之外的配置：`server.port=80`、`spring.datasource.url=jdbc:h2:file:./data/db/scriptbox;DB_CLOSE_ON_EXIT=FALSE`、`spring.sql.init.mode=always` + `schema-locations=classpath:db/schema.sql`、`spring.servlet.multipart.max-file-size=10MB / max-request-size=12MB`、`bigdata.environment-name`（前端环境标签）、`logging.level`。

**运行时覆盖**：`system_setting` 表的 `cleanup.*Days` 覆盖对应 `retention-*-days`，通过 `PUT /api/admin/settings` 白名单写入，无需重启。

**mock 与真实环境切换**：只需 `scriptbox.mock: false` + 上传真实 keytab。`mock=true` 时跳过 kinit wrapper（脚本直接执行），`TenantService.testConnectivity` 返回模拟 kinit / klist 输出。生产目标为 Linux 节点，默认 `bash` 走 PATH。

**本地 Windows / Git Bash 开发**：`pom.xml` 的 `git-bash` profile 把 `scriptbox.shell-executable` 覆盖为 `C:/gitbash/bash.exe` 并通过 surefire `argLine` 传给测试（`-Dscriptbox.shell-executable=...`）。原因：`C:\Windows\system32\bash.exe` 是 WSL 转发器，会把 `C:\Users\...` 吞成 `C:Users...`，任何带路径参数的 bash 调用都失败。默认 `bash.exe` 路径含空格而 Maven `argLine` 无法安全传带空格的 `-D` 值，因此用目录联接 `C:\gitbash`；装在别处可用 `-Dgit.bash.path=...` 覆盖。**生产不受影响。**

---

## 8. 测试覆盖

`src/test/java` 共 28 个测试类 + 1 个基类，158 个 `@Test` 方法。

`BaseIntegrationTest`：`@SpringBootTest(webEnvironment=NONE)`，把 `data-dir` / `scripts-dir` / `keytabs-dir` / `executions-dir` 指向 `./target/test-data`，H2 指向 `./target/test-db/scriptbox`，`@BeforeEach` 清空 tenant / script / script_param / history 表并删除磁盘上的脚本与执行目录。（类注释还写着「与生产共用 `./data/`」，已过时。）

| 测试类 | 覆盖点 |
| --- | --- |
| `ScriptExecutorTest` | success / failed / stderr 分流 / 超时 / 大输出不死锁 |
| `ProcessRunnerTest` | 退出码 0 / 非零退出码 / 超时销毁 / cancel 翻转 permit / stdout-stderr 分离 / 返回前句柄已释放 |
| `ExecutionCancellationTest` | 取消杀进程并写 CANCELLED / 未知 ID / 二次取消幂等 / 杀子孙进程 / 活跃计数 / permit finished / 非取消仍成功 / 多次取消后闸门仍可用 |
| `ConcurrencyGateTest` | `allowConcurrent=false` 拒绝重复 / `=true` 放行 / 槽位上限 / 执行后释放 / 取消后释放 / 可用槽位对齐配置 / `setMaxConcurrent` 归一 / 多次执行不泄漏槽位 |
| `ScriptServiceTest` | 脚本 CRUD + 参数校验 + 脚本正文保存 |
| `ScriptVersionServiceTest` | 创建即 v1 / 保存正文追加版本 / 回滚产生含旧内容的新版本 / 未知版本失败 |
| `PresetServiceTest` | 创建列表 / apply 保真 / 原始 JSON 往返 / 更新覆盖 / 删除 / summary 只含 id+name |
| `GlobalVariableServiceTest` | 创建列表注入 / 禁用不注入 / 敏感遮罩 / 非法 key / 重复 key / 删除 |
| `ConditionalParamTest` | 隐藏参数不传给脚本 / 规则命中时传递 / notEquals 取反 / 空规则恒可见 / 引用未知参数保存时拒绝 / 非法 JSON 拒绝 / 解析往返 / null 规则 helper |
| `ExecutionSnapshotTest` | 正常执行捕获快照 / 每次唯一 / 脚本改动后变化 / 按快照重跑执行原正文 / 快照缺失抛错 / 未知 execution 抛错 / 敏感全局变量被遮罩 |
| `RiskLevelExecutionTest` | DANGEROUS 无 token 拒绝 / 带 token 通过 / 错 token / READ_ONLY 与 WRITE 不需 token / bypass 跳过 / 创建默认 READ_ONLY / 显式 DANGEROUS 持久化 / 非法值拒绝 |
| `DryRunTest` | preview 不启进程 / 敏感全局变量遮罩 / 未知脚本失败 |
| `ArtifactServiceTest` | 脚本写产物并注册 / artifacts 目录缺失容错 / 超大文件跳过不致命 / `resolveSafe` 拒绝穿越与绝对路径 / 接受主键与文件名 / 重跑替换旧行 |
| `FileParameterTest` | 上传后执行 / 拒绝非上传路径 / 拒绝非法文件名 |
| `ScriptPackageServiceTest` | 导出导入往返 / 重名生成副本 / 拒绝 Zip Slip / 缺 manifest / 缺 script.sh |
| `PrecheckServiceTest` | 无配置即跳过 / kerberos 缺 principal 失败 / 命令存在与不存在 / 文件存在与缺失 / 目录可写 / 非法配置 JSON / PreCheck 失败短路执行 |
| `ScriptTemplateTest` | 5 个内置模板 / listEnabled / findByCode 往返与未知 / 从模板创建真实 Script / 幂等 upsert / API 响应形态 |
| `SyntaxCheckTest` | 合法正文 / if 不匹配 / 引号不匹配 / 空正文合法 / 无 shebang 也可 / create / saveScriptBody / update 拒绝坏文件 / `toMap` 序列化 / preflight 不落库 / preflight 空正文 |
| `ResultParserServiceTest` | 正常解析 / WARNING 不覆盖执行器状态 / 非法 JSON 不破坏执行 / 缺失时字段为空 / `readStructured` 缺失返回 null |
| `ScenarioServiceTest` | 按序执行全部步骤 / 默认首次失败中止 / continueOnFailure 跑完 / replaceSteps 清旧 / 删除级联 |
| `BatchServiceTest` | 全行执行并附 batchId / 空行拒绝 / summary 字段形态 / 空 batch 查询 |
| `HistoryService` 相关（`UxEndpointsTest`） | `/api/system/info` 环境名 / 收藏开关往返 / 复制脚本含参数 / 最近脚本去重排序 / 按状态过滤 / 按关键字过滤 |
| `ExecutionContextTest` | params 防御性拷贝 / null 变空 map / Builder 透传路径 / 字段不可变 |
| `SensitiveDataMaskerTest` | maskEnv 只遮敏感值 / null 与空 / 无敏感项 identity / 命令行字符串脱敏 / 大小写不敏感 / keytab 路径 / 列表脱敏 / 空列表 noop |
| `StoragePathServiceTest` | 路径生成约定 / assertInside 接受 / 拒绝兄弟目录 / 拒绝相对穿越 / 缺失路径 isInsideReal=false / 存在子路径 true / safeList 缺失目录空 / 返回条目 |
| `TenantServiceTest` | 创建列表查询 / 更新与禁用 / 删除 |
| `H2PersistenceTest` | 写入确实落到 H2 文件 |
| `ApiSmokeTest` | 租户 API 端到端往返 |

尚未覆盖：`AdminController` 清理三段式（preview → CLEAN → execute）的端到端、`CleanupExecutor` 真实文件系统删除、`ScenarioController` / `BatchController` 的 `@WebMvcTest` 切片、`CommandExecutor.exec(CommandSpec)` 的独立契约测试。

---

## 9. 已知问题与未完成项

> 下列条目均已按当前代码核实，**只列至今仍成立**的项；早期报告里已修复的问题不再出现在这里。

### 9.1 仍成立的风险与技术债

| # | 问题 | 位置 / 说明 |
| --- | --- | --- |
| 1 | `ScriptExecutor` 仍是单类编排者（803 行），承担准备回调、PreCheck、快照、收尾、dry-run、重放、日志读取、参数校验。`prepareContext` 只余 1 行委托，但 `finalizeExecution` 仍约 90 行，`ExecutionFinalizer` 未创建。 | `executor/ScriptExecutor.java` |
| 2 | 大量方法仍超长：`validateAndCoerce`、`readUpTo`、`buildSnapshotJson`、`ScriptExecutor.preview`、`CleanupService.preview`、`ScenarioService.run`、`ProcessRunner.launch`。 | 多个类 |
| 3 | API 错误契约双轨：`ExecutionController.run` / `rerun` 仍手写 try/catch 并调 `ApiResponse.error(message)`（落 `errorCode=INTERNAL_ERROR`）；`AdminController.cleanupExecute` 对「缺 previewId」「token 不匹配」也返回 `ApiResponse.error(...)`，**把 `CleanupService` 本可抛出的 `CONFIRM_TOKEN_MISMATCH` 挡在外面**，前端拿不到精确错误码；`GlobalVariableController.get` 的「variable not found」同样退化为 `INTERNAL_ERROR`。 | `controller/ExecutionController.java`、`AdminController.java`、`GlobalVariableController.java` |
| 4 | 3 个 Controller 仍是 `@Autowired` 字段注入（`FileUploadController` / `ScriptPackageController` / `ScriptTemplateController`），与其余类的 `@RequiredArgsConstructor` 约定不一致，且不能用 Mockito 单测替换依赖。 | 上述 3 个类 |
| 5 | `Map<String,Object>` 契约仍散落：`BatchService.summarize`、`ArtifactService.listView`、`FileUploadService.savePending`、`FileUploadService.promoteForExecution`、`ScenarioService.RunEntry.toMap`、`ScriptService.relatedCounts`、`ScenarioService.relatedCounts`、`AdminController` 的 `@RequestBody Map` + `readInt`、`CleanupService` 手拼 `retention_json` JSON 字符串。字段名靠字符串约定，拼错编译期无感知，`@RequestBody Map` 也无法用 Bean Validation。 | 多个 service / controller |
| 6 | **同一实体两套响应契约**：`GlobalVariableService.listSummary()` 返回 `variableValueSet`，而 `GlobalVariableController.get()` 返回 `hasValue`。同一个 `GlobalVariable` 的列表与详情形状不同。 | `GlobalVariableService` / `GlobalVariableController` |
| 7 | 状态归一化两套实现：`ExecutionStatus.of()` 与 `HistoryService.deriveStatus`（后者还额外依赖 `success` / `timeout` 旧字段）。`HistoryService.listFiltered` 的 `success` / `failed` / `timeout` 过滤走旧字段而不是 `status` 列，只有 `cancelled` 走 `status`——脚本被 `result.json` 覆盖过 status 时列表筛选与详情状态可能不一致。 | `model/ExecutionStatus` / `service/HistoryService` |
| 8 | 条件参数规则两套实现：执行期 `ScriptExecutor.validateAndCoerce`（过滤 + 丢弃不可见参数）与 UI 期 `ScriptService.filterVisible`。两处必须手工同步。 | 上述两个类 |
| 9 | `VisibleWhen` 的类注释声明「引用的 param 缺失时规则视为不匹配」，但 `isVisible` 把缺失值当 `""`，因此 `notEquals` 在 param 缺失时返回 **true**（可见）。注释与实现不一致。 | `model/VisibleWhen` |
| 10 | `VisibleWhen` 用自己的静态 `new ObjectMapper()`，绕过 `JacksonConfig` 的共享实例。 | `model/VisibleWhen` |
| 11 | 批量失败**没有原因明细**：`BatchSummary` 只有 `failed` 计数，失败 message 仅进日志；调用方拿到汇总后无法在 UI 上显示「哪一行为什么失败」。 | `service/BatchService` |
| 12 | 输出上限是**事后截断**而非硬配额：进程运行期间 stdout/stderr 可以超出 `max-output-bytes`（受 timeout 约束），结束后才 `truncateToBudget`；两个流各自独立计上限。 | `executor/ProcessRunner.truncateToBudget` |
| 13 | 句柄释放探测是启发式：`canBeDeleted` 用 `FileChannel.open(WRITE)` 成功与否判断「内核是否已释放写句柄」，但这**不等于「能否删除」**（Windows 上他人持句柄时照样能打开）。因此 `awaitHandlesReleased` 通过后仍可能删不掉，`ProcessRunnerTest` 依赖 `@TempDir(cleanup = NEVER)` 才稳定。同类结构性缺口：Git Bash 下 `ProcessHandle.descendants()` 在 shell 退出后可能枚举不到真正的孙进程，`destroyTree` 的「等 descendants 消失」无法覆盖这种情况。 | `executor/ProcessRunner`、`service/ExecutionGate` |
| 14 | `executionId` / `batchId` 由 `AtomicLong(System.currentTimeMillis() * 1000)` 起自增的应用内计数器生成，**不来自数据库序列**：JVM 重启后 ID 可能落入历史 ID 区间（H2 文件模式单进程，风险低但未消除）。 | `ScriptExecutor.nextExecutionId`、`BatchService.batchCounter` |
| 15 | MDC 的 HTTP 层注入只读 **query parameter**（`req.getParameter`）。`POST /api/executions` 这类 JSON body，若把 `executionId` / `batchId` / `scenarioId` 放在 body 里，HTTP 线程的日志不会带这些 key（执行线程内由 `MdcContext` 补上）。 | `config/RequestMdcFilter` |
| 16 | `PreviewStore` 是进程内内存：重启即丢；TTL 10 分钟**硬编码不可配**。`CleanupService.preview` 的执行目录候选用目录 `mtime`，历史行候选用 `start_time`，两套截止标准；运维手工 `touch` 执行目录会改变候选集合。 | `service/PreviewStore`、`service/CleanupService` |
| 17 | `schema.sql` 里 `preset_variable` 与 `file_upload` 已无实体 / Mapper / 调用方（DDL 仍会创建空表）；`execution_history.summary` 列有 DDL 但 `ExecutionHistory` 实体未映射，永远不会被写入。 | `db/schema.sql` |
| 18 | 产物扫描**不递归**：只注册 `artifacts/` 一层下的常规文件，脚本写在子目录里的内容不会被注册（但目录仍会被 Cleanup 整体删除）。 | `service/ArtifactService.scanAndRegister` |
| 19 | `result.json` 的 1 MB 上限是 `ResultParserService` 内的私有常量，未做成 `scriptbox.*` 配置项（早期计划里的 `max-result-json-bytes` 从未实现）。 | `service/ResultParserService.MAX_BYTES` |
| 20 | 默认超时 `600` 仍分散在多处：`db/schema.sql` 的 `DEFAULT 600`、`ScriptBoxProperties.defaultTimeoutSeconds`、`ExecutionContext.Builder` 的注释哨兵、以及脚本 / 场景 / 导入路径上的回退逻辑。改默认值需要同步多处。 | 多处 |
| 21 | `CleanupService` 的「`system_setting` 覆盖 > 配置」优先级由 4 个 `settings.getInt(K_*, props.get*)` 手写，未收敛为统一的 `RetentionPolicy`。 | `service/CleanupService` |
| 22 | 可变静态内部类仍多：`ScenarioService.RunResult` / `RunEntry`、`CleanupExecutor.CleanupReport` / `SkipFail`、`ArtifactService.ResolvedArtifact`、`PreviewStore.CleanupPreview` / `CandidateOutcome` / `Totals` / `ControlledPaths`、`SyntaxCheckService.SyntaxResult`、`ScriptTemplateService` 的结果类。只有 `BatchSummary`、`PrecheckReport`、`ExecutionContext`、`ExecutionPreview`、`CommandSpec/Result`、`ProcessRequest/Result` 是 record。 | 多个 service |
| 23 | `sha256Hex` 在 `ScriptExecutor` 与 `ArtifactService` 各有一份，且都用 `String.format("%02x", b)`（逐字节装箱 + StringBuilder），未用 `HexFormat.of()`。 | 上述两个类 |
| 24 | `BusinessErrorCode` 30 个枚举中 7 个无抛出点（见 §2.2）；`PRECHECK_FAILED` 未被 PreCheck 失败路径使用（该路径直接写 `ExecutionStatus.PRECHECK_FAILED` 到 DB）。 | `exception/BusinessErrorCode` |
| 25 | `QueryWrapper` 字符串列名仍是主流，`LambdaQueryWrapper` 迁移未做（列名拼错无编译期保护）。 | 多个 service |
| 26 | 测试盲区：清理三段式（preview → CLEAN → execute）与真实递归删除没有端到端用例；`AdminController` / `BatchController` / `ScenarioController` 无切片测试；`CommandExecutor.exec` 无独立契约测试。 | `src/test/java` |

### 9.2 早期计划的完成度（明确标注）

| 计划项（来自早期 PLAN 与评审） | 状态 |
| --- | --- |
| 脚本风险等级 + DANGEROUS 二次确认（`CONFIRM`） | **已完成**（`RiskLevel` + `ExecutionPreparer.validateScript` + `RiskLevelExecutionTest`） |
| 条件参数 / 联动动态表单（`visibleWhenJson`） | **已完成**（`VisibleWhen` + `ConditionalParamTest`）；但执行期与 UI 期规则仍双实现（§9.1 #8） |
| 执行中取消任务 | **已完成**（`ExecutionGate.cancel` + `/cancel` + `/state` + `/active`） |
| 防重复执行 + 全局并发上限 | **已完成**（`script.allow_concurrent` + `ExecutionGate` 原子准入 + `max-concurrent`），并顺带修掉 `waitFor` 误报超时的缺陷 |
| 日志搜索 / 高亮 / 下载 | 前端能力，另见前端文档；后端只提供截断读取（`max-log-bytes`） |
| 脚本模板（5 个内置） | **已完成**（`script_template` + `DataInitializer.seedTemplates` + `ScriptTemplateController`） |
| 执行快照（`snapshotJson` + `scriptSha256`）+ 重放 | **已完成**（`captureSnapshot` / `rerunFromSnapshot`，重放不再改写原脚本文件） |
| Shell 保存前语法检查（`bash -n`） | **已完成**（`SyntaxCheckService`，走 `CommandExecutor` 带硬超时；bash 不可用时退化启发式） |
| 执行产物（ARTIFACT_DIR + `execution_artifact`） | **已完成**（`ArtifactService`；**计划中的「200MB 总量上限」未实现**，只有单文件与文件数两个上限） |
| **自动清理（`@Scheduled` 每日凌晨 + `bigdata.retention.*`）** | **未实现且有意不做**：清理完全手动（`@EnableScheduling` 未引入）；实际配置前缀是 `scriptbox.retention-*-days`，与计划里的 `bigdata.retention.*` 不同 |
| 清理预览 TTL 可配 | **未实现**：`PREVIEW_TTL_MS` 仍硬编码 10 分钟 |
| `max-result-json-bytes` 配置项 | **未实现**：1 MB 硬编码 |
| `pom.xml` 重复 Lombok 声明清理 | **已完成**（现在只有一处） |
| `StoragePathServiceTest` 平台耦合（硬编码 `/`） | **已完成**（改用 `Paths.get(...)` 拼断言） |
| 子命令统一契约（`CommandExecutor` 收口 3 处 `ProcessBuilder`） | **已完成**（`CommandSpec` / `CommandResult`；`SyntaxCheckService` 与 `TenantService.testConnectivity` 都已迁移） |
| `shell-executable` 可配 | **已完成**（默认 `bash`；`-Pgit-bash` profile 供 Windows 开发） |
| Controller 不再碰 Mapper / `QueryWrapper` / `getMapper()` / 裸 `ObjectMapper` | **已完成** |
| `BatchService` 硬编码 200 / 8 进配置 + 行数校验去重 | **已完成**（`max-batch-rows` / `max-batch-concurrency` + `validateRows`） |
| 死配置 `maxArtifactTotalBytes` | **已删除**（未接线，按计划直接移除） |
| `application.yml` 补 `max-concurrent` | **已完成** |
| `86_400_000L` → `Duration.ofDays(n).toMillis()` | **已完成**（`CleanupService`） |
| `ScriptExecutor` 拆解为 `ExecutionPreparer` + `CommandBuilder` + `ExecutionFinalizer` | **部分完成**：前两者已落地，`ExecutionFinalizer` 未创建（§9.1 #1） |
| DTO 边界收敛（`Map<String,Object>` 大幅减少） | **部分完成**：`ExecutionPreview`、`PrecheckReport`、`ExecutionStateView`、`RunningExecutionView`、`BatchSummary` 已类型化；其余见 §9.1 #5 |
| 异常体系全量落地（`BusinessErrorCode`） | **部分完成**：23/30 已使用，`ApiResponse` 已加 `errorCode`；但 Controller 层仍有手写 try/catch 与 `ApiResponse.error(message)`（§9.1 #3） |
| 全项目构造器注入 | **部分完成**：3 个 Controller 仍字段注入（§9.1 #4） |
| 配置与常量收口（单一可信源） | **部分完成**：批量边界与死配置已解决；默认超时多副本与 `CleanupService` 手写优先级仍在（§9.1 #20/#21） |
| `Map` 返回改 record、`QueryWrapper` → `LambdaQueryWrapper` | **未完成**（§9.1 #22/#25） |
| 前端契约改名 / 前端适配新 `errorCode` | **未完成**，需要与 `frontend/src/api/*.js` 对齐后再改 |

---

## 10. 重大重构摘要

按主题合并去重（不按报告逐份罗列）：

| 重构主题 | 结论 / 收益 |
| --- | --- |
| **统一子进程执行契约**（`CommandExecutor` / `CommandSpec` / `CommandResult`） | 项目从「3 份互不知情的 `ProcessBuilder`」收敛为「1 份实现、3 个调用点」。消除了 `SyntaxCheckService.tryBashN` 与 `TenantController.test` 里两处**无超时 `waitFor()`**——它们挂在 Tomcat 工作线程上，其中一个还是所有写路径的强制关卡并被 `/api/scripts/syntax-check` 高频调用，可挂死请求线程。shell 可配后 Windows 本机也能跑测试。 |
| **`ScriptExecutor` 编排拆解（第一、二步）** | `ExecutionPreparer`（纯规划，不碰磁盘试探）与 `CommandBuilder`（命令 + 环境唯一表达）落地，`preview` 与真实执行共享同一份参数解析与命令语义，消除了 dry-run 与真实执行的语义漂移。`ScriptExecutor` 从 885 行降到 803 行。 |
| **executionId 生命周期归位** | ID 在入口生成一次，删掉「临时 ID 先 promote 一次、真 ID 再 promote 一次」的重复；`upsertHistory` 合并三处 insert/update 判定。直接消灭了「每次带文件参数执行都留下永不清理的孤儿目录 + 重复文件副本」的真实磁盘债务，并让原本零调用点的 `FileUploadService.cleanupPending` 真正生效。 |
| **输出通道与进程生命周期加固** | 输出改为**内核重定向**，逐字节忠实（不再改写 `\r\n`）、零解码、不占内存、免疫管道缓冲死锁；输出上限改为结束后截断；`waitFor` 返回 false 后再问 `isAlive()`，修正「退出码 7 被改写成 `-1` 并误报超时」；destroy 后验证是否真死，残留记 ERROR + `lingering`；修复 `readUpTo` 短读返回未初始化零字节的缺陷。**取消不再导致执行线程永挂、槽位不释放。** |
| **并发准入原子化**（`ExecutionGate` 取代 `RunningExecutionRegistry`） | 从 check-then-act（`prepareContext` 查 → `startProcess` 再查 → `ProcessRunner` 才登记）改为单临界区原子完成「去重 + 槽位 + 占位」，`max-concurrent` 从「尽力而为」变成硬上限；`Permit implements AutoCloseable` 使槽位释放与业务收尾严格对齐；统一 `destroyTree` 并区分「许可从未存在」与「许可被取消后回收」（后者必须空转，否则会留下无人能取消的进程）。 |
| **快照重放改用临时副本** | 重放不再 `Files.writeString(original, body)` 改写原始脚本文件（并发读 / 并发执行会看到「快照体被当成当前体」），改为写到本次执行自己的 `executionDir/script.sh`，随 Cleanup 回收，同时去掉了「预生成 ID + finally 删除」的临时文件生命周期。 |
| **kinit wrapper 生命周期与权限** | 随机文件名的 0700 临时文件，执行结束（成功 / 失败 / 超时 / 取消 / 异常）在 `finally` 无条件删除，消除了「wrapper 长期残留在执行目录里暴露 keytab 路径」；wrapper 内的 shell / kinit 路径改为读配置。 |
| **分层修正与 DI 统一** | 3 个 Controller 不再注入 Mapper / 手写 `QueryWrapper`；删除 `ScriptService.getMapper()` 这一「Service 泄漏持久层」的先例；2 处裸 `new ObjectMapper()` 改为注入容器实例；7 个 Controller 转 `@RequiredArgsConstructor`；13 个 Service / 组件转构造器注入（`MdcContext`、`FileSystemUtils` 抽出）。 |
| **清理与统计的 DRY + 递归删除语义修正** | `directorySize` / `deleteRecursively` 收敛到 `FileSystemUtils`：递归删除改用 `FileVisitor` post-order（叶子先删），取代「按路径字符串长度倒序」这种不可靠的近似深度排序；统计改为不跟随软链接、单文件失败只 WARN 不把总数归零。 |
| **PreCheck 策略族内部收敛** | `items()` 的逐字重复解析上提到 `AbstractJsonListPrecheckStrategy`；`type()` → `configKey()`；显示名从「Service 里的 `type.equals("kerberos")` 特判 + 策略内的 `DISPLAY_NAME`」两处并成 `displayName(item)` 一处；返回类型 `Map<String,Object>` → `PrecheckReport` + `CheckResult` record，调用方不再写 `Boolean.TRUE.equals(map.get("ok"))`；删除死代码 `PrecheckService.CheckResult`。**外层 Strategy + Registry 架构保持不变**，新增检查类型仍只需实现接口。 |
| **异常体系与错误码落地** | `ApiResponse` 增加稳定 `errorCode`（`BusinessErrorCode.name()`），`GlobalExceptionHandler` 统一翻译 `BusinessException` / `IllegalArgumentException` / `IllegalStateException` / 校验失败 / 未捕获异常；`AdminController` 的 3 个局部 `@ExceptionHandler`（把错误码降级成 `"PREVIEW_EXPIRED: "` 字符串前缀）已删除。抛出点从 2 个增长到 23 个错误码。 |
| **BatchService 异常与线程池** | 并行执行的 `f.get()` 不再静默吞异常（区分 `InterruptedException` 并恢复中断标志、其它异常记 ERROR + 堆栈）；线程池放进 try/finally + `shutdownNow()`；`BatchSummary` 改 record；行数校验去重；并发上限与行数上限进配置。 |
| **基础设施抽取（`StoragePathService` / `SensitiveDataMasker` / `JacksonConfig` / `MdcContext` / `TextDecoder`）** | 路径生成与越界检查从散落 7 个类收敛为单一安全门；敏感值脱敏从各处自实现收敛为一个组件（env / 命令行字符串 / 命令行列表三种形态）；`ObjectMapper` 统一为 Bean；MDC 从手写 put/remove 收敛为 `AutoCloseable` 的 `MdcContext`；`TextDecoder` 按 BOM 嗅探 + 宽松解码，消除了「脚本跑成功但日志页抛 `MalformedInputException`」。 |
| **日志与可观测性** | 中文业务日志贯穿执行生命周期；`RequestMdcFilter` 注入 HTTP 上下文；`StartupLogger` 输出生效配置与关键路径；Logback 滚动保留从 30 天收紧到 3 天、总量上限从 3GB 收到 1GB（产物 / stdout / stderr 仍走各自的保留天数，只手工清理）。 |
| **测试基线重建** | 从「131 个 Error + 1 个 Failure」恢复到全绿；根因是 Windows 上 `bash` 解析到 WSL 会吞掉 Windows 路径的反斜杠（131 个 Error）与 `StoragePathServiceTest` 硬编码 POSIX 分隔符（1 个 Failure）。现在 28 个测试类 158 个用例，覆盖取消 / 并发 / 快照 / 风险等级 / 条件参数 / 产物 / 保留清理 / 语法检查 / Zip Slip 等关键路径。 |

---

## 11. 变更约定

### 11.1 新增一个脚本：不需要改 Java

这是项目的核心设计目标。新增脚本的完整路径：

1. 在 `#/scripts` 上传 `.sh`（或从模板创建），保存时会强制跑 `bash -n`；
2. 在脚本编辑页声明 0~N 个动态参数（类型取自白名单：`text` / `textarea` / `number` / `select` / `boolean` / `date` / `file`）；
3. 按需配置执行前检查（`precheck_config_json`：`kerberos` / `commands` / `files` / `writableDirectories`）；
4. 按需设置风险等级、允许并发、超时、参数方案（Preset）、版本。

脚本从 `ProcessBuilder` 拿到的是 `--key value` 形式的长选项，以及 `EXECUTION_ID` / `EXECUTION_DIR` / `ARTIFACT_DIR` / 全部启用的全局变量。**脚本正文存在磁盘上，不进 JAR**，因此新增 / 修改脚本无需重新打包或重启。`$ARTIFACT_DIR` 下的文件会在执行后自动注册为产物。

### 11.2 扩展参数类型白名单

`ScriptService.ALLOWED_TYPES` 是唯一白名单（`Set.of("text","number","select","boolean","date","textarea","file")`），`replaceParams` 用它校验，前端下拉也依赖它。新增一种类型的改动点固定为：

1. `ScriptService.ALLOWED_TYPES` 加类型名；
2. `ScriptExecutor.validateAndCoerce` 的 `switch(type)` 加分支（决定空值、类型转换、枚举校验、以及传给 shell 的字符串形态）；
3. 前端 `ParamForm` 加对应控件渲染分支；
4. 补一个测试（参考 `ConditionalParamTest` / `ScriptServiceTest`）。

`boolean` 的「required 但空值合法」与 `number` / `select` 的严格校验都在 `validateAndCoerce` 里，新增类型必须明确这三件事：默认值语义、空值语义、非法值是否抛 `IllegalArgumentException`。

### 11.3 新增一种 PreCheck 检查

实现 `PrecheckStrategy`（或 JSON 列表型检查可 `extends AbstractJsonListPrecheckStrategy`，只在子类声明 `configKey()` 并实现 `check()`），注册为 Spring Bean 即可——`PrecheckStrategyRegistry` 自动收集，`PrecheckService.run` 无需改动。显示名由 `displayName(item)` 决定（默认 `key:item`，Kerberos 覆写为 `Kerberos`）。注意：**只有 `precheck_config_json` 里显式出现的 key 才会运行**，空配置等同「无 PreCheck」。

### 11.4 日志规范

- 业务日志（INFO / WARN / ERROR）**用中文**，覆盖创建 / 更新 / 删除 / 启停 / 复制 / 执行 / 清理 / 取消 / 失败等生命周期节点；关键节点以 `模块: 动作` 前缀（如 `execution:`、`batch:`、`scenario:`、`cleanup:`、`artifact:`、`result.json:`、`upload:`、`package:`、`precheck:`）。
- 日志中的变量用 `key={}` 占位 + 参数传入，**不拼字符串**；循环内与高频路径不写 INFO（避免 log spam）。
- 异常：ERROR 带完整堆栈；WARN 只带 message（噪音控制）。
- **绝不打印**：keytab 内容或路径之外的敏感信息、password / token / secret / credential 的真实值、敏感全局变量的真实值、脚本正文、参数值、清理 / 产物的绝对路径（对外响应）。所有命令与环境变量在打印前必须经 `SensitiveDataMasker`。
- MDC 只放标识符（`executionId` / `batchId` / `scenarioId` / `scriptName` / `tenantName` / `requestUri`），统一用 `MdcContext.of(...)` 的 try-with-resources 挂载；**不要手写 `MDC.put` / `MDC.remove`**。
- 第三方协议头 / Logback 与 Spring 的日志 key 保持英文，便于日志采集与告警规则。

### 11.5 改动执行链路的硬约束

- 命令必须 `List<String>` 提交，**禁止 `bash -c` 拼接**；shell / kinit / klist 路径一律读 `ScriptBoxProperties`。
- 子进程必须走 `CommandExecutor`；用户脚本执行还必须先 `ExecutionGate.acquire` 并 try-with-resources 持有 `Permit`。
- **不要退回「应用侧读管道处理子进程输出」**：管道 + 强杀 = 读取线程永远等不到 EOF，且从另一个线程 `close()` 一个正被 `read()` 的流并不生效。
- **不要让 `process.waitFor` 落入 `@Transactional`**：Shell 执行全过程禁止处于数据库事务中。
- 删除 / 清理路径必须经 `StoragePathService`（`assertInside` / `isInsideReal`）+ 软链接跳过 + 运行中二次确认；新增受控目录种类也必须走它，**不要改写其算法**。
- 新增受控目录时同步在 `StoragePathService` 加生成方法，并把回收责任明确指派给「Cleanup 管辖（在 `executionsRoot` / `logsRoot` 下）」或「调用方 finally 立即回收（如 `uploads/`）」之一。
- 客户端永不下发路径。文件参数必须来自 `/api/uploads` 的 pending 根；产物 / 日志只按主键或相对名查表。
- `executionId` 在一次执行中只允许生成一次，且必须在任何磁盘副作用之前确定。
