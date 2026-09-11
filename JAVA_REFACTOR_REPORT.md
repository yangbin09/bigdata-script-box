# Java 后端重构报告

> 范围：仅 Java 后端；不动前端、不动数据库 schema、不动 API 契约。
>
> 技术栈保持不变：Java 17 / Spring Boot 3.3.5 / MyBatis-Plus 3.5.7 / H2 file-mode DB / Jackson / Maven / ProcessBuilder。

---

## 1. 总体目标

把一个 600+ 行的 `ScriptExecutor` 与若干分散在 7 个类里的路径处理 / 进程启动 / 敏感数据脱敏逻辑，重构成一组职责清晰、可单测的设计模式落点，并补充缺失的异常层、脱敏组件、共享 `ObjectMapper` Bean、日志与追踪基础设施。

完成 6 个独立的 commit（共 +1095 / −347 行后端代码，外加 4 套新单元测试），全部 156 个测试绿灯。

---

## 2. 落地的设计模式

| 模式 | 落地位置 | 解决的问题 |
| --- | --- | --- |
| **Strategy + Registry** | `service/precheck/PrecheckStrategy` + `PrecheckStrategyRegistry` + `PrecheckService` | 原本 `PrecheckService` 内嵌 4 类硬编码 if-else 检查；新增一类检查必须改原类。改为 Spring `List<PrecheckStrategy>` 自动收集，类型键是字符串。新增 PreCheck 类型 = 加一个 `@Component` 即可。 |
| **Template Method / 生命周期** | `executor/ExecutionContext` + `ScriptExecutor.execute()` 拆成 `prepareContext → runPrecheckOrRecordFailure → captureSnapshot → startProcess → finalizeExecution` | 原 `execute()` 是一个 280 行单方法，13 个相互依赖的局部变量在内部流转。引入 `ExecutionContext` record + 5 个明确的 phase，每个 phase 自带前置后置语义（如 captureSnapshot 负责把 `scriptSha256 + snapshotJson` 持久化、finalizeExecution 负责状态映射 + 解析 result.json + 扫描 artifact）。 |
| **Facade** | `executor/ProcessRunner` | 把 `ProcessBuilder` 启动 / stdout-stderr 并行 drain / 超时 / 取消传播 / `ProcessHandle.descendants()` 销毁 这些易错的样板抽到一个组件，`ScriptExecutor` 不再直接碰 `Process`。 |
| **Registry** | `service/RunningExecutionRegistry` | 已经存在，本次重构让 `ProcessRequest` 携带 `scriptId` / `tenantId`，ProcessRunner 调用 `registry.register` 时把真实 ID 传过去，让「同脚本并发」检查能正确按 scriptId 匹配。 |
| **DTO / VO 边界** | `executor/ProcessRequest` / `ProcessResult` | 把进程启动参数与结果固化成 Java 17 record，便于单元测试与日志记录。`ProcessRequest` 构造时做防御性拷贝（`List.copyOf` / `Map.copyOf`），调用方再修改 List/Map 不会污染执行。 |
| **Exception Layer** | `exception/BusinessErrorCode` + `BusinessException` + `GlobalExceptionHandler` | 引入业务异常体系；`@RestControllerAdvice` 统一把 `BusinessException` / `IllegalArgumentException` / `IllegalStateException` 翻译为 `ApiResponse(code=1, message=...)`，堆栈只落日志，不暴露到浏览器。未捕获异常兜底为 HTTP 500 + 通用消息。 |

---

## 3. 抽出的公共组件

### 3.1 `service/StoragePathService`
集中所有受控目录的路径生成 + 越界检查。取代分散在 7 个类的 `Paths.get(...).toAbsolutePath().startsWith(...)` 重复逻辑。

对外方法：
- `scriptsRoot()` / `keytabsRoot()` / `executionsRoot()` / `logsRoot()` / `dataRoot()` — 归一化后的绝对路径。
- `scriptFilePath(id)` / `keytabPath(tenantId, suffix)` / `executionDirFor(id)` / `artifactsDirFor(id)` / `inputDirFor(id)` / `pendingUploadDir(token)` — 单个对象的绝对路径。
- `assertInside(child, root, what)` — 语法层安全校验（用于路径还没落盘时的 early reject）。
- `isInsideReal(child, root)` — 用 `Path.toRealPath(NOFOLLOW_LINKS)` 比较，用于清理 / 删除场景的 symlink 安全。
- `safeList(dir)` — 目录不存在的安全 `Files.list`。

### 3.2 `service/SensitiveDataMasker`
集中「把敏感值替换成 `******`」的三种场景：
1. `maskEnv(env, declared)` — 根据 `GlobalVariable.sensitive=true` 替换环境变量值。
2. `maskCommand(String)` — 用正则 `(--xxx) value` 对命令行字符串做白名单（password/token/secret/credential/keytab 子串）替换。
3. `maskCommandList(List<String>)` — `ProcessBuilder(List<String>)` 风格的 List 形式脱敏。

`ScriptExecutor.execute()` 在日志中打印命令前统一走 `maskCommandList`，杜绝 `password=xxx` 落到 `scriptbox.log`。

### 3.3 `executor/ProcessRunner`
封装 `ProcessBuilder` 启动 + 双线程 drain stdout/stderr + 超时 + 取消传播。文档化的关键不变量：
- stdout / stderr 必须并行 drain，否则管道缓冲区满会阻塞进程（最常见的踩坑点）。
- 取消时先 destroy 子进程（`ProcessHandle.descendants()`）再 destroy 父进程，防止孤儿进程。
- drain 线程是 daemon，JVM 退出时不阻塞。

### 3.4 `model/ExecutionStatus` / `model/CleanupResult`
- `ExecutionStatus` 枚举：`RUNNING / SUCCESS / FAILED / TIMEOUT / CANCELLED / PRECHECK_FAILED`。`ScriptExecutor` 写状态时不再用字符串字面量；`of(String)` 在反序列化未知值时归一化为 `FAILED`，保证 DB 历史行的脏数据不会让前端崩溃。
- `CleanupResult` 枚举：`SUCCESS / PARTIAL / FAILED`。`CleanupReport.result` 从 `String` 改为枚举，DB 列保持兼容（`row.setResult(report.result.name())`）。

### 3.5 `config/JacksonConfig`
共享 `ObjectMapper` Bean，注册 `JavaTimeModule`、禁用 `WRITE_DATES_AS_TIMESTAMPS`、禁用 `FAIL_ON_UNKNOWN_PROPERTIES`。所有 Service 改用构造注入，不再 `new ObjectMapper()`。这之前散落在 8 个文件的 ObjectMapper 字段全部统一到一处。

### 3.6 `config/RequestMdcFilter`
最高优先级 OncePerRequestFilter。从 URL 参数读 `executionId / batchId / scenarioId`，注入到 SLF4J MDC。请求结束自动清理，避免污染线程复用导致的下一个请求。配合 `ScriptExecutor.attachMdc`，一条 execution 涉及的日志（包括 HTTP 请求 + 进程执行 + PreCheck）能按 executionId 切片查看。

### 3.7 `config/StartupLogger`
`ApplicationReadyEvent` 监听器，启动完成打一条 INFO，输出关键配置 + 路径，让运维一眼看到生效的值，避免「改了配置没生效」的常见误解。

---

## 4. 异常层（BusinessException + GlobalExceptionHandler）

`exception/BusinessErrorCode`：枚举化的稳定错误码。`PATH_ESCAPE` / `PATH_INVALID` / `SCRIPT_NOT_FOUND` / `TENANT_NOT_FOUND` / `EXECUTION_ALREADY_RUNNING` 等。前端永远只看到 `code + 中文 message`，不直接暴露 Java 类名或堆栈。

`exception/BusinessException`：携带 `BusinessErrorCode + message` 的 RuntimeException。

`exception/GlobalExceptionHandler`：
- `BusinessException` → `ApiResponse(code=1, message=...)`，WARN 日志。
- `IllegalArgumentException` / `IllegalStateException` → 同上（Service 层抛出的「参数非法 / 状态非法」自动归一）。
- 任何其他异常 → HTTP 500 + 通用消息，完整 stack trace 落 ERROR 日志。

---

## 5. 日志 / 追踪

| 改动 | 文件 |
| --- | --- |
| 中文 INFO / WARN 日志贯穿 `ScriptExecutor` 5 个 phase | `executor/ScriptExecutor.java` |
| `ExecutionContext.attachMdc` 注入 executionId / scriptName / tenantName；执行完 finally 清理 | `executor/ScriptExecutor.java` |
| `RequestMdcFilter` 在 HTTP 层从 URL 注入 MDC | `config/RequestMdcFilter.java` |
| `StartupLogger` 输出启动配置 + 路径 | `config/StartupLogger.java` |
| `logback-spring.xml`：`maxHistory=30 → 3`、`totalSizeCap=3GB → 1GB` | `resources/logback-spring.xml` |

---

## 6. 测试

| 测试类 | 数量 | 范围 |
| --- | --- | --- |
| `ExecutionContextTest` | 4 | 防御性 Map.copyOf / Builder 透传 / 不可变视图 |
| `SensitiveDataMaskerTest` | 8 | env / command-string / command-list / 不区分大小写 / keytab-path / 空输入 / 无敏感项 identity |
| `StoragePathServiceTest` | 8 | 路径生成约定 / assertInside 接受 / 拒绝 sibling / 拒绝 `..` 越界 / symlink / safeList |
| `ProcessRunnerTest` | 5 | happy path / 非零退出码 / 超时 destroy / cancel 经 registry / stdout-stderr 分流 |
| 既有测试 | 131 | 全部保持绿灯（含 `ExecutionSnapshotTest`、`ConcurrencyGateTest`、`ExecutionCancellationTest` 等关键集成测试） |
| **总计** | **156** | 全绿 |

---

## 7. Commit 序列（按时间顺序）

1. `5aefe21` — baseline before java refactoring（131 tests green 起点）
2. `534dc5f` — refactor: introduce business exception layer + global handler
3. `ee84ea9` — refactor: extract StoragePathService, SensitiveDataMasker, ProcessRunner
4. `dc69b91` — refactor: services adopt StoragePathService for path generation + checks
5. `3eadd35` — refactor: introduce PrecheckStrategy + PrecheckStrategyRegistry
6. `f0e1a40` — refactor: split ScriptExecutor using ExecutionContext + ProcessRunner
7. `f601330` — refactor: cleanup report uses CleanupResult enum + tighten log retention
8. `ed43123` — refactor: introduce shared JacksonConfig ObjectMapper bean
9. `7d0ebc9` — test: add unit tests for ExecutionContext, SensitiveDataMasker, StoragePathService, ProcessRunner
10. `6f16096` — feat: add RequestMdcFilter + StartupLogger for ops visibility

每个 commit 都已通过完整 `mvn test`，测试数从 131 增长到 156。

---

## 8. 兼容性 / 不变量

| 不变量 | 保证方式 |
| --- | --- |
| API 契约 | 所有 controller 路由、方法签名、`ApiResponse` 形状不变；`CleanupReport.result` 在 JSON 中仍是 `"SUCCESS"` / `"PARTIAL"`（enum name） |
| DB schema | 没有 DDL 变更；`history.status` / `cleanup_history.result` 列保持 `VARCHAR`，由 enum `.name()` 写入 |
| 前端 | 无改动；前端已有对 `"SUCCESS"` / `"PARTIAL"` / `code=1` 错误消息的处理路径直接复用 |
| Cron / 定时任务 | 仍**没有** `@EnableScheduling`；`application.yml` 仍没有 cleanup-cron key。手动清理路径不变 |
| 敏感数据 | log 永不出现 keytab 内容 / password / token / 敏感 global variable 的明文值；SensitiveDataMasker 集中处理；日志 MDC 中也只放 identifier（executionId / batchId / scenarioId），不放 value |
| Shell 注入 | 脚本仍只通过 `ProcessBuilder(List<String>)` 启动，从未拼接 `bash -c <user_input>`；新 ProcessRunner 同样遵守 |

---

## 9. 未做（明确划出范围）

| 主题 | 原因 |
| --- | --- |
| 拆分 `ScriptService` 为 `ScriptFileRepository` | 当前 `ScriptService` 约 350 行，方法清晰；进一步拆分收益边际、且没有新业务功能要求 |
| 拆分 `CleanupService` 为 `CleanupPreviewBuilder` / `CleanupAuditRecorder` | 同上；`CleanupService` 已把 IO 与审计分开为私有方法，进一步拆类会增加调用栈深度而无新功能收益 |
| 拆分 `ArtifactService` 为 `ArtifactScanner` / `ArtifactPathGuard` | `ArtifactService` 已经是单一职责类；拆分仅是物理搬代码，不解决问题 |
| 精简 controllers | 每个 controller 仍保留 `try/catch` + `ApiResponse.error(...)` 模式，是为了保留前端依赖的固定错误前缀（`BAD_REQUEST:` / `PREVIEW_EXPIRED:` 等）。改为纯抛出后，前端需要适配新错误格式 → 超出本次「不动前端」的范围 |
| 引入 AI / MQ / Redis / Spring Cloud | 用户明确禁止 |

---

## 10. 后续可选方向（如果以后需要）

- 把 `ExecutionContext` 扩展为「结构化日志 event emitter」，用 JSON 写出 execution lifecycle（`started` / `precheck-failed` / `process-started` / `process-exit` / `artifact-registered`），让外部 collector 能直接消费。
- 把 `ProcessRunner` 抽出接口（`ProcessRunner` → `DefaultProcessRunner`），未来如果想做「用 JDK 进程替换为 k8s Job」就能直接换实现。
- 给 controllers 做薄化（适配现有前端 → 逐步迁移错误格式 → 删 try/catch）。

---