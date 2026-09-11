# BigData Script Box — Java 后端重构计划

## 一、当前 Java 包结构

```
src/main/java/com/bigdata/scriptbox/
├── ScriptBoxApplication.java               (启动类，仅启用 @EnableAsync，不带 @EnableScheduling)
├── config/
│   ├── DataInitializer.java                (223 行，启动种子：mock tenant + 5 mock scripts + 5 templates)
│   ├── InMemoryMultipartFile.java          (内存 MultipartFile，DataInitializer 用)
│   ├── ScriptBoxProperties.java            (@ConfigurationProperties，所有 scriptbox.* 配置)
│   └── WebConfig.java                      (SPA fallback 路由)
├── controller/                             (14 个 Controller)
│   ├── AdminController.java                (140 行，cleanup preview/execute + system settings)
│   ├── BatchController.java
│   ├── ExecutionController.java            (214 行，含 active/state/cancel/rerun/stdout/stderr/result/artifacts)
│   ├── FileUploadController.java
│   ├── GlobalVariableController.java
│   ├── HistoryController.java
│   ├── PrecheckController.java
│   ├── PresetController.java
│   ├── ScenarioController.java
│   ├── ScriptController.java               (300 行，最重，CRUD + params + precheck + copy + from-template)
│   ├── ScriptPackageController.java        (import/export)
│   ├── ScriptTemplateController.java
│   ├── ScriptVersionController.java
│   ├── SystemController.java
│   └── TenantController.java               (125 行，含 test() 直接调用 ProcessBuilder)
├── dto/
│   ├── ApiResponse.java                    (统一返回 {code, message, data})
│   └── ExecutionRequest.java               (52 行执行请求 DTO)
├── entity/                                 (11 个 MyBatis-Plus 实体)
│   ├── CleanupHistory.java
│   ├── ExecutionArtifact.java
│   ├── ExecutionHistory.java
│   ├── GlobalVariable.java
│   ├── Scenario.java
│   ├── ScenarioStep.java
│   ├── Script.java
│   ├── ScriptParam.java
│   ├── ScriptPreset.java
│   ├── ScriptTemplate.java
│   ├── ScriptVersion.java
│   └── SystemSetting.java
├── executor/
│   └── ScriptExecutor.java                 (660 行，最重的核心类)
├── mapper/                                 (11 个 Mapper 接口 + 7 个 XML)
├── model/
│   ├── RiskLevel.java                      (READ_ONLY / WRITE / DANGEROUS 字符串常量 + requireValid)
│   └── VisibleWhen.java                    (条件显示规则解析)
└── service/                                (16 个 Service)
    ├── ArtifactService.java                (311 行，artifact 扫描 + 下载安全)
    ├── BatchService.java                   (179 行，并发批量执行)
    ├── CleanupExecutor.java                (277 行，实际删除)
    ├── CleanupHistoryService.java
    ├── CleanupService.java                 (341 行，预览 + 执行 + 历史 + 保留天数)
    ├── FileUploadService.java              (133 行，上传 + promote + cleanup)
    ├── GlobalVariableService.java          (136 行)
    ├── HistoryService.java                 (115 行，列表/筛选/最近)
    ├── PrecheckService.java                (182 行，4 类检查：Kerberos/Command/File/Dir)
    ├── PresetService.java
    ├── PreviewStore.java                   (146 行，cleanup preview 临时存储)
    ├── ResultParserService.java            (101 行，result.json 解析)
    ├── RunningExecutionRegistry.java       (98 行，并发运行注册)
    ├── ScenarioService.java                (168 行)
    ├── ScriptPackageService.java           (255 行，ZIP import/export)
    ├── ScriptService.java                  (313 行，CRUD + 版本快照触发 + 文件持久化 + 语法检查)
    ├── ScriptTemplateService.java
    ├── ScriptVersionService.java
    ├── SyntaxCheckService.java             (151 行，bash -n + 可选 shellcheck)
    ├── SystemSettingService.java
    └── TenantService.java                  (106 行)
```

测试代码 26 个，约 3539 行。

## 二、当前主要类职责（按规模）

| 类 | 行数 | 职责 |
|---|---|---|
| `ScriptExecutor` | 660 | 参数校验、命令构造、ProcessBuilder、kinit 包装、Process 生命周期、stdout/stderr 异步消费、结果解析触发、artifact 扫描触发、状态决策、snapshot 构建、rerun、log 截断读取 |
| `CleanupService` | 341 | preview 构建、执行编排、保留天数读取、history 写入 |
| `ScriptService` | 313 | CRUD + 文件持久化 + 语法检查 + VisibleWhen 校验 + 版本快照触发 + 文件清理 |
| `ArtifactService` | 311 | 扫描 + 路径安全 + sha256 + mime + listView + 下载 |
| `ScriptController` | 300 | 14 个 endpoint：CRUD + params + body + precheck + copy + from-template + related-counts + syntax-check |
| `CleanupExecutor` | 277 | 实际删除 + 路径安全 + symlink/escape 防护 |
| `ScriptPackageService` | 255 | ZIP 打包/解包 + 防 Zip Slip |
| `DataInitializer` | 223 | 启动种子 |
| `ExecutionController` | 214 | execute + preview + active + cancel + state + stdout/stderr + result + rerun + artifacts + 下载 |
| `PrecheckService` | 182 | 4 类 PreCheck（Kerberos/Command/File/Dir） |
| `BatchService` | 179 | runSequential + runParallel |
| `ScenarioService` | 168 | run + replaceSteps |
| `SyntaxCheckService` | 151 | bash -n + shellcheck |
| `PreviewStore` | 146 | preview 临时存储 |
| `AdminController` | 140 | cleanup + system settings |

## 三、发现的问题

### 3.1 超大类 / 超长方法
- `ScriptExecutor.execute()` 单个方法约 280 行（59~342），参数准备、PreCheck、Process 启动、Process 等待、cancel 检测、drain 等待、结果写回全部耦合在一起。
- `ScriptExecutor` 还兼任 rerun、preview、stdout/stderr 读取、snapshot 构建 4 类职责。
- `CleanupService.preview()` 单方法 145 行，把 4 类候选扫描全塞在一个方法里。

### 3.2 重复代码
- **路径校验**：`Paths.get(...).toAbsolutePath().normalize()` + `startsWith(controlledRoot)` 这套写法在 `ScriptExecutor`、`CleanupService`、`CleanupExecutor`、`ArtifactService`、`FileUploadService`、`ScriptService`、`TenantService` 至少 7 处出现。
- **目录 size 计算**：`directorySize(Path)` 在 `CleanupService` 和 `CleanupExecutor` 各一份，且实现基本相同。
- **SHA-256 计算**：`ScriptExecutor.sha256Hex(byte[])` 和 `ArtifactService.sha256Hex(Path)` 是两份不同签名的实现，本质相同。
- **语法检查 + try-with-resources 临时文件**：`SyntaxCheckService` 和 `ScriptExecutor` 各有自己 drain 异步线程 + ProcessBuilder 的样板。
- **ProcessBuilder 启动 + env putAll + kinit wrapper**：在 `ScriptExecutor` 里出现两次（带 kinit / 不带 kinit）。
- **过滤 visibleWhen**：`ScriptExecutor.validateAndCoerce()` 和 `ScriptService.filterVisible()` 实现相同逻辑两遍。
- **Sensitive GlobalVariable 掩码**：`ScriptExecutor.preview()` 和 `ScriptExecutor.buildSnapshotJson()` 各写一遍同样的 `for(v : enabled) if (sensitive) env.put(..., "******")`。
- **tenant keytab 路径 / 启用判断**：`ScriptExecutor` 在执行路径中、`TenantController.test()` 在 mock 分支里都写同样的 `tenant.getKeytabPath() != null && !tenant.getKeytabPath().isBlank()`。

### 3.3 大量 if/else 与字符串散落
- `ScriptExecutor.validateAndCoerce()` 用 `switch(type)` 处理 6 种参数类型，加上 default 分支，未来增加类型时要改这里。
- `ScriptExecutor` 里硬编码状态字符串 `"RUNNING" / "PRECHECK_FAILED" / "CANCELLED" / "TIMEOUT" / "SUCCESS" / "FAILED"`。
- `ExecutionController`、`HistoryService` 也重复这些字符串。
- `CleanupExecutor.computeResult()` 用 `"SUCCESS" / "PARTIAL"` 字符串（`CleanupHistory` 实体有常量，但 executor 直接写字面量）。

### 3.4 Controller 业务逻辑
- `ScriptController.create()` / `update()` / `copy()` 内含字段拼装、InMemoryMultipartFile 构造、params 复制等业务装配逻辑，约 80 行。
- `ExecutionController` 直接调用 `executor.execute()` 然后捕获 `IllegalArgumentException / IllegalStateException / IOException` 包装成 `ApiResponse.error(...)`，每个 endpoint 都重复。
- `TenantController.test()` 在 Controller 内调用 `ProcessBuilder` 跑 `kinit` / `klist`，应该是 Service 层职责。

### 3.5 Service 职责过多
- `ScriptExecutor` 是典型上帝类。
- `CleanupService` 同时负责预览、执行、历史、保留天数、保留天数获取。
- `ScriptService` 同时负责 CRUD、文件持久化、语法检查、可见性校验、版本快照触发。

### 3.6 异常处理
- 没有任何 `BusinessException` / `ResourceNotFoundException` 等业务异常体系，全部用 `IllegalArgumentException` / `IllegalStateException` / `RuntimeException`。
- 没有 `RestControllerAdvice` 全局异常处理；每个 Controller 自己写 `try/catch (IllegalArgumentException e) return ApiResponse.error(...)`。
- 至少 11 处 `catch(Exception)`：
  - `ScriptExecutor` 3 处 result.json/artifact scan/snapshot/rerun 全部 `catch(Exception)`；
  - `BatchService` 2 处 row execute / future get 全部 `catch(Exception)`；
  - `ScenarioService` 1 处 step execute；
  - `CleanupService` 1 处 history record；
  - `CleanupExecutor` 多处删除路径上的 `catch(Exception)`（这是合理的，因为单文件失败不应中断整体）；
  - `FileUploadService` 3 处 cleanup / cleanupPending；
  - `ScriptService.delete()` 2 处 preset/version 级联清理 `try{...}catch(Exception ignored){}`。
- 部分异常被吞掉（`catch(Exception ignored){}`）但缺少中文注释说明为什么可以忽略。

### 3.7 日志体系
- 全工程仅 30 处 `log.info / log.warn / log.error / log.debug` 调用，日志非常稀薄。
- 几乎全是英文：`log.warn("result.json parse failed for executionId={}: {}", ...)`。
- 启动时没有 BigData Script Box 启动完成的总览日志。
- 关键节点（开始执行、超时、取消、kinit 调用、危险脚本确认、清理完成）大多只是 `log.info` 简短英文。
- 没有 MDC，无法在一行日志中关联 executionId / batchId / scenarioId。
- 没有统一的日志格式说明（例如常量中文摘要）。

### 3.8 中文注释
- 关键类（`ScriptExecutor`、`PrecheckService`、`CleanupService`、`RunningExecutionRegistry`）有详细英文 JavaDoc。
- 但中文业务解释、为什么这么设计、什么场景会触发仍然不足。
- 缺少「为什么不用 bash -c」「为什么 path 必须校验」「为什么 Cleanup 不自动」这种 *设计决策* 注释。

### 3.9 配置管理
- `ScriptBoxProperties` 已经统一了 `scriptbox.*` 配置，结构良好；但 `bigdata.environment-name` 仍散在 `SystemController` 里用 `@Value`。
- 缺少 `ExecutionProperties`、`StorageProperties` 拆分，但当前总字段数不超过 25，不必强拆。

### 3.10 路径 / 安全
- `ScriptExecutor` 内多处 `Paths.get(scriptPath)` 和 `Paths.get(props.getScriptsDir())` 散落；调用方没有统一校验脚本路径必须落在 scripts dir 下。
- `ArtifactService.resolveSafe()`、`CleanupExecutor.deleteExecutionDir()`、`FileUploadService.promoteForExecution()` 三处实现「路径必须在受控目录下」的逻辑，但写法不同（一个用 `normalize().startsWith()`，一个用 `toRealPath().startsWith()`）。
- `ScriptExecutor.rerunFromSnapshot()` 内写入脚本文件的方式（备份原文 → 写 snapshot → 还原）虽然 try/finally，但 README 没说明。

### 3.11 ProcessBuilder 重复
- `ScriptExecutor` 两处启动（带 kinit / 不带 kinit），env 注入三遍。
- `TenantController.test()` 又一处 `ProcessBuilder` 跑 kinit/klist，没有错误处理模板。
- `SyntaxCheckService.runProcess()` 自带 drain + waitFor + destroyForcibly 模板。
- 没有统一的 `ProcessRunner` 抽象。

### 3.12 返回类型
- 核心结果（`PreviewStore.CleanupPreview`、`RunningExecutionRegistry.RunningExecution`、`BatchService.BatchSummary`、`HistoryService.RecentScript`）都用 public mutable class，不是 record。
- ScriptExecutor 的 preview 返回 `Map<String,Object>`，key 字符串散落。

### 3.13 其他
- `ScriptBoxApplication` 没有中文启动日志。
- `logback-spring.xml` 当前保留 30 天 (`maxHistory=30`)、3 GB (`totalSizeCap=3GB`)，需要改成 **3 天 + 1 GB**。
- 文件名 `scriptbox.log` 历史名 `scriptbox.%d{yyyy-MM-dd}.log.gz` 不带 `application` 前缀，但项目说明约定是 `application.log`。当前应用日志文件名保持现状不破坏现有清理逻辑。

## 四、准备重构的类

按依赖顺序（底层先做）：

1. **新增** `exception/BusinessException.java`（统一业务异常，含 `code`、`message`）。
2. **新增** `exception/BusinessErrorCode.java`（枚举：`SCRIPT_NOT_FOUND`、`SCRIPT_DISABLED`、`TENANT_NOT_FOUND`、`TENANT_DISABLED`、`EXECUTION_ALREADY_RUNNING`、`PARAMETER_INVALID`、`PRECHECK_FAILED`、`FILE_INVALID`、`CONFIRM_TOKEN_MISMATCH` 等）。
3. **新增** `exception/GlobalExceptionHandler.java`（`@RestControllerAdvice`，统一输出 `{code, message}`；保留 HTTP 200，body 区分业务错误）。
4. **新增** `model/ExecutionStatus.java`（枚举：`RUNNING / SUCCESS / FAILED / TIMEOUT / CANCELLED / PRECHECK_FAILED`），提供字符串与枚举互转，保留数据库字符串兼容。
5. **新增** `model/CleanupResult.java`（枚举：`SUCCESS / PARTIAL / FAILED`）。
6. **新增** `service/StoragePathService.java`（统一脚本、keytab、execution、artifact、input、log 路径生成 + 路径安全校验 + 路径包含检测）。
7. **新增** `service/SensitiveDataMasker.java`（统一敏感字段掩码：GlobalVariable、Snapshot、Command、ExecutionRequest 输出）。
8. **新增** `executor/ProcessRunner.java`（封装 ProcessBuilder 启动、drain、timeout、destroyForcibly、cancel、ProcessHandle descendants、waitFor + 超时处理；返回 `ProcessResult` record）。
9. **新增** `executor/ExecutionContext.java`（统一一次执行所需的全部数据：executionId、script、tenant、params、execDir、artifactDir、env、registry、preset、request）。
10. **新增** `executor/CommandBuilder.java`（从 ExecutionContext 构造 ProcessBuilder.command + 环境变量，含 kinit 包装）。
11. **新增** `service/ExecutionSnapshotService.java`（snapshot 构建 + 重放，从 ScriptExecutor 抽出）。
12. **重构** `executor/ScriptExecutor.java`：
    - `execute()` 拆成 `validatePreConditions` / `prepareExecutionRecord` / `runProcess` / `finalizeExecution` 4 个私有方法；
    - 委托 ProcessRunner；
    - 使用 ExecutionContext 替换大参数列表；
    - 不再自己写 SHA-256、directorySize（委托 StoragePathService）。
13. **重构** `service/PrecheckService.java`：
    - 引入 `PrecheckStrategy` 接口 + `KerberosPrecheckStrategy` / `CommandExistsPrecheckStrategy` / `FileExistsPrecheckStrategy` / `DirectoryWritablePrecheckStrategy`；
    - 由 Spring 自动注入 List<PrecheckStrategy>，PrecheckService 变成 dispatcher。
    - 只有当判断逻辑足够多样化、未来需要扩展时才这么做——如果只是 4 类简单 if，则保留单类结构。当前判断逻辑确实 4 段独立、无共享代码，**值得用策略模式**。
14. **重构** `service/ParameterCoercionService.java`（从 ScriptExecutor 抽出 validateAndCoerce 与 visibleWhen 过滤；可选，不拆太多类）。
15. **重构** `service/CleanupService.java`：
    - 拆出 `CleanupPreviewBuilder`（负责构建 preview）；
    - `execute()` 拆出 `CleanupAuditRecorder`（写入 cleanup_history）；
    - 引入 ExecutionStatus / CleanupResult 枚举。
16. **重构** `service/CleanupExecutor.java`：
    - 委托 StoragePathService 做路径安全；
    - 单一职责就是「按 preview 列表逐项删除」。
17. **重构** `service/ArtifactService.java`：
    - 拆 `ArtifactScanner`（扫描 + sha256 + 限制）与 `ArtifactPathGuard`（路径安全）；
    - 引入 `ArtifactView` record 作为 listView 返回。
18. **重构** `service/ScriptService.java`：
    - 拆 `ScriptFileRepository`（持久化 / 路径安全 / 清理）；
    - Service 只保留 CRUD + VisibleWhen 校验 + 触发 snapshot。
19. **重构** `controller/ExecutionController.java`、`controller/ScriptController.java`、`controller/AdminController.java` 等：
    - 让 GlobalExceptionHandler 接管异常，Controller 内不再写 try/catch；
    - 薄 Controller：参数接收 + 调用 Service + 返回；
    - `ScriptController.createFromTemplate()` 中 ObjectMapper 创建提到 `ObjectMapperConfig`。
20. **新增** `config/ObjectMapperConfig.java`（Spring 注入共享 ObjectMapper Bean，去掉散落的 `new ObjectMapper()`）。
21. **新增** `config/MdcFilter.java`（Servlet Filter 或 HandlerInterceptor，从请求头读取 executionId / batchId / scenarioId 写入 MDC；执行结束自动清理）。
22. **新增** `config/StartupLogger.java`（实现 `ApplicationListener<ApplicationReadyEvent>`，输出中文启动完成日志）。
23. **重构** `service/ScriptBoxProperties.java`：保持不动，只补充 KDoc；不需要拆。
24. **修改** `logback-spring.xml`：保留 3 天，总上限 1GB。
25. **新增** `model/SensitiveFieldNames.java`：列出「keytab-path」「variable-value-sensitive」「password」「token」「secret」作为敏感字段白名单，供 SensitiveDataMasker 使用。

## 五、准备使用的设计模式

| 模式 | 解决的问题 | 应用点 |
|---|---|---|
| **策略模式 Strategy** | PrecheckService 内部 4 段互相独立的判断逻辑；以后可能新增 check 类型 | `PrecheckStrategy` 接口 + 4 个实现 + Spring 自动注入 `List<PrecheckStrategy>` |
| **策略模式 Strategy** | ParameterCoercionService 中 6 种参数类型的转换（text/number/select/boolean/date/textarea）| 合并到统一 `ParameterTypeStrategy` 注册表，按 type 路由 |
| **工厂 + 注册表** | 把 Spring Bean 按 type 注册到 Map<key, Strategy> | `PrecheckStrategyRegistry`、`ParameterTypeRegistry` |
| **责任链 Chain of Responsibility** | ScriptExecutor 在执行前的多重校验（脚本存在/启用、租户存在/启用、并发控制、并发上限、参数合法、Precheck 通过）| 仅在引入后让 ScriptExecutor.execute 变清晰时使用。当前校验是顺序 if-else + IllegalArgumentException，**已有 5 段**（脚本状态 / 并发去重 / 租户状态 / 参数 / Precheck），引入 `ExecutionCheck` 列表可以让 execute 第一行变成「依次跑 check」。如果引入能显著减少行数才用 |
| **模板方法 / 生命周期** | 普通执行 / 批量 / 场景三种调度流程共享：准备 Execution → 启动 Process → 等结束 → 写历史 → 收集结果 | 用组合（ExecutionContext + ExecutionService + ProcessRunner）替代继承抽象类。**不引入 AbstractExecutionTemplate**——组合优于继承 |
| **Facade 门面** | ExecutionController / BatchController / ScenarioController 当前都直接调用 ScriptExecutor + 多 Service | 引入 `ExecutionFacade`（execute / preview / cancel / state / rerun），Controller 全部委托给它。**只在 Controller 出现明显 Service 拼接时引入** |
| **Registry 自动注入** | 把 `@Component` Strategy 收集到 Map<key, Strategy> | 与策略模式组合使用 |
| **Builder（限定）** | ExecutionContext 字段多（executionId/script/tenant/params/execDir/env/...）| 用 `ExecutionContext.builder()`，但只在字段 ≥ 6 时才上 builder，**不过度使用** |

## 六、明确**不**使用设计模式的地方

| 模式 | 不使用的原因 |
|---|---|
| **DDD 目录结构（domain / application / infrastructure / port）** | 项目规模仅 ~6700 行；增加目录深度只会拖慢阅读 |
| **EventBus / 事件总线** | 引入依赖；当前业务没有跨模块事件需求 |
| **复杂的 Builder 链** | 超过 6 字段才用；不强制每个 Service 都加 Builder |
| **模板方法 + 抽象类层级** | 执行流程的三种调用方（直接 / 批量 / 场景）参数差异较大，组合 + 模板比继承更清晰 |
| **完整仓储模式 Repository** | MyBatis-Plus 已足够；再加一层 Repository 是无意义包装 |
| **AOP 自定义切面** | 当前没有横切关注点（如审计、限流）。日志切面将来需要时再做 |
| **Redis / 缓存抽象** | 当前一次 preview 命中数 < 200，加缓存收益 < 复杂度 |
| **Spring Cloud / Config Server** | 单体应用，无需微服务化 |
| **Guava / Apache Commons 工具库** | JDK 17 + Spring 自带工具已够；不引入 |
| **Optional 全程传参** | Service 内部查找 → 抛 BusinessException；不返回 Optional |
| **CommonUtils / StringUtils2 / BigDataUtils** | 工具类只承载无状态通用逻辑；业务方法放 Service |
| **DTO / VO 全套** | 简单 CRUD 的 Request/Response 保持当前形式；只在复杂接口（执行接口、cleanup、preview）做边界 |
| **完整 Event Sourcing** | 当前 ExecutionHistory 行就是审计；不需要单独事件流 |

## 七、API 兼容方案

**目标：所有现有 HTTP 行为不变（URL、method、body、response code 0/1 保持不变）。**

| 不变的部分 |
|---|
| 所有 Controller URL（`/api/scripts`、`/api/executions`、`/api/admin/cleanup/*` 等） |
| 所有 endpoint 的 request body 字段 |
| 所有 endpoint 的 response shape（`ApiResponse<T>` 的 `{code, message, data}`） |
| ExecutionHistory 实体字段（`status` 列存的是字符串，数据库行不动） |
| Script/ScriptParam/Tenant/GlobalVariable 等实体字段 |
| cleanup_history 表结构 |

| 改动但兼容的部分 |
|---|
| `ScriptExecutor` 内部方法签名变化：调用方 `BatchService` / `ScenarioService` / `ExecutionController` 适配新签名 |
| 异常类型从 `IllegalArgumentException` 改为 `BusinessException`：但 `GlobalExceptionHandler` 会把它转成相同的 `ApiResponse(code=1, message=...)` |
| 状态字符串写入仍用 `"SUCCESS" / "FAILED" / ...`（来自 `ExecutionStatus` 枚举的 `name()`） |
| `record` 类型在 Service 之间传递，但 Controller 返回的 JSON 结构不变 |
| `StoragePathService` 替代分散的 `Paths.get(...)`：目录布局不变 |

## 八、数据库兼容方案

**目标：现有 H2 schema 不变。**

- 不创建 / 删除 / 重命名表。
- 不修改列。
- 不修改已存在行的值。
- 所有 `scriptbox.*` 配置项保留，application.yml 不需要为重构做改动。
- `mock: true` 默认行为保持。
- 数据库中存在的 status 字符串（"RUNNING" / "SUCCESS" / "FAILED" / "TIMEOUT" / "CANCELLED" / "PRECHECK_FAILED"）一律保留，新增 `ExecutionStatus` 枚举时把字符串字面量集中到枚举常量，`ExecutionStatus.name()` 即为数据库值。

## 九、测试方案

### 9.1 现有测试
共 26 个测试类。**全部测试必须保持通过。**

- `ApiSmokeTest` — HTTP API round-trip
- `ArtifactServiceTest` — artifact 扫描 + sha256 + 下载安全
- `BatchServiceTest` — 批量执行 + 顺序/并发
- `ConcurrencyGateTest` — 并发上限 + allowConcurrent + 槽位释放
- `ConditionalParamTest` — visibleWhen 条件参数
- `DryRunTest` — preview
- `ExecutionCancellationTest` — cancel
- `ExecutionSnapshotTest` — snapshot 写入 + 重放
- `FileParameterTest` — file input 参数
- `GlobalVariableServiceTest` — 全局变量
- `H2PersistenceTest` — H2 持久化
- `PrecheckServiceTest` — PreCheck 4 类
- `PresetServiceTest` — Preset
- `ResultParserServiceTest` — result.json 解析
- `RiskLevelExecutionTest` — DANGEROUS 脚本 + CONFIRM token
- `ScenarioServiceTest` — 场景执行 + abort on failure
- `ScriptExecutorTest` — 脚本执行
- `ScriptPackageServiceTest` — ZIP import/export
- `ScriptServiceTest` — 脚本 CRUD
- `ScriptTemplateTest` — 模板
- `ScriptVersionServiceTest` — 版本快照 + 回滚
- `SyntaxCheckTest` — bash -n
- `TenantServiceTest` — 租户
- `UxEndpointsTest` — UX 端点

### 9.2 重构验证策略

每个重构步骤完成后执行：
```
mvn -Dmaven.test.skip=false -DskipFrontend test
```
期望：所有测试通过。

### 9.3 重点手工验证（本地 mock 环境）
- 直接执行 success / failed / timeout / stderr-mix 5 个内置 mock 脚本
- 危险脚本 confirm 流程
- PreCheck 执行失败
- file 参数上传
- 取消正在运行的 sleep 脚本
- 并发触发（同时跑两个 sleep）
- Snapshot rerun
- Cleanup preview / execute
- 批量 / 场景执行
- 历史列表筛选
- Artifact 下载
- 关键敏感路径在日志中应显示 `******`

### 9.4 回归清单（重构后必须跑一遍）
1. ✅ 正常执行（success mock）
2. ✅ 执行失败（failed mock）
3. ✅ stderr 输出（stderr-mix mock）
4. ✅ timeout（timeout mock，3 秒后被强制结束）
5. ✅ cancel（sleep 脚本被 cancel）
6. ✅ precheck failed（脚本配 kerberos=true 且 mock tenant 无 keytab）
7. ✅ result.json 解析（脚本里 echo 写 result.json）
8. ✅ file 参数（upload → execute → 路径透传）
9. ✅ artifact 扫描（脚本写文件到 $ARTIFACT_DIR）
10. ✅ batch（5 行参数）
11. ✅ scenario（3 步骤，第一个失败后续跳过）
12. ✅ preset（应用 preset 后 params 落到命令）
13. ✅ script version（rollback 到 v2）
14. ✅ 危险脚本 CONFIRM
15. ✅ 并发保护（allowConcurrent=false 时拒绝）
16. ✅ 手动 cleanup preview
17. ✅ 手动 cleanup execute
18. ✅ H2 重启后数据还在
19. ✅ 应用日志 3 天滚动

## 十、重构步骤（按提交顺序）

每个步骤对应一次 git 提交；提交信息参考用户给出的命名风格：

```
1. chore: baseline before java refactoring
2. refactor: introduce business exception layer + global handler
3. refactor: introduce ExecutionStatus / CleanupResult enums
4. refactor: extract StoragePathService for path safety
5. refactor: extract SensitiveDataMasker
6. refactor: extract ProcessRunner for Process lifecycle
7. refactor: split ScriptExecutor into ExecutionContext + lifecycle methods
8. refactor: introduce PreCheckStrategy + registry
9. refactor: introduce ParameterTypeStrategy + registry
10. refactor: introduce ExecutionFacade
11. refactor: split CleanupService preview/execute/audit
12. refactor: split ArtifactService scanner/path-guard
13. refactor: split ScriptService file repo
14. refactor: thin controllers, delegate exceptions to global handler
15. refactor: shared ObjectMapper bean
16. refactor: add MDC filter for executionId / batchId / scenarioId
17. refactor: add chinese log messages and class-level JavaDoc
18. refactor: clarify comment for sensitive masking and path safety
19. chore: keep application log only 3 days
20. test: add focused tests for new abstractions
21. docs: JAVA_REFACTOR_REPORT.md
```

## 十一、风险与缓解

| 风险 | 缓解 |
|---|---|
| ScriptExecutor 重写破坏并发语义 | ConcurrencyGateTest、ExecutionCancellationTest、ExecutionSnapshotTest、BatchServiceTest 已覆盖；每次小步提交后跑全套测试 |
| ProcessRunner 重构导致 stdout/stderr 死锁 | 复用现有 drain 线程实现；写单测 `processRunner_drainsConcurrentStreams` |
| 新增 GlobalExceptionHandler 漏掉某些异常导致 500 | 写单测覆盖 `BusinessException` / `ResourceNotFoundException` / 其他未处理异常 |
| StoragePathService 路径语义与原代码不完全一致 | 复用 `Paths.get(...).toAbsolutePath().normalize().startsWith(...)` 原算法；以 `ArtifactServiceTest` / `ScriptPackageServiceTest` 作为安全网 |
| 日志切换中文破坏日志收集 | 关键日志加结构化 key（如 `executionId={}`），便于机器解析；message 主体中文 |
| application.log 改名破坏 Cleanup 路径 | **不重命名**日志文件，保持 `scriptbox.log` |

## 十二、最终交付物

1. 重构后的 Java 代码（所有现有测试 + 新测试通过）
2. `JAVA_REFACTOR_REPORT.md`（重构报告，含设计模式使用清单、API/DB 兼容性说明、测试结果）
3. 一个完整可运行的 JAR（部署到 80 端口）
4. 3 天滚动的 application log
