# JAVA_CODE_REVIEW_REPORT.md

> 工具：综合应用以下 Skill 规则
>
> - `/java-core:java-review`（ducpm2303/claude-java-plugins）
> - `java-development-skill` 中的 `code-review/cr-*.md` 与 `core/java-exception-handling.md`
> - `spring-boot/sb-exception-handling.md`, `spring-boot/sb-mybatis-plus.md`
> - `/java-core:java-concurrency-review`
> - `/java-core:java-design-pattern`
> - `/java-core:java-api-review`
> - `/java-spring:java-logging`
> - `spring-boot/sb-project-structure.md`（暂未读全，但已有上下文）
>
> 检查范围：`src/main/java/com/bigdata/scriptbox/**`（83 个 Java 文件）
> 本阶段**未做任何代码改动**，仅产出报告。

---

## 0. 范围

仅对**项目自研**的 Java 代码做审查；Lombok / MyBatis-Plus / Spring 自身的代码不审。

| 分组 | 数量 |
| --- | --- |
| Controller | 23 |
| Service（含 precheck 子包） | 19 |
| Executor（4） | 4 |
| Mapper（13） | 13 |
| Entity（13） | 13 |
| DTO / Model | 5 |
| Config（含 Filter / Startup / Multipart） | 7 |
| Exception | 3 |
| 总计 | 87（部分类同时属于多分组） |

---

## 1. P0 — 严重安全 / 数据风险

> 必须**先修**，否则生产环境可能出现：
> - 进程 / 子进程残留
> - keytab / password 等敏感信息落日志
> - 路径穿越 / 任意文件删除

### P0-1：`ProcessRunner.run` 缺少 try/finally 保证 registry.unregister

- **位置**：`executor/ProcessRunner.java:66-125`
- **规则依据**：`code-review/cr-resource-leak.md` §"Spring/MP — usually safe, but watch the edges" +
  `code-review/cr-concurrency.md` §"Concurrent primitives — `ConcurrentHashMap`"
- **问题**：`registry.register(executionId, ...)` 后到 `return` 之间任何异常（`pb.start()` /
  `process.waitFor` 的 `InterruptedException` / `process.getInputStream()` / `getErrorStream()`
  抛 IOException / `drainAsync` 内 IO 等）都不会触发 `registry.unregister(executionId)`。
  `RunningExecutionRegistry.activeCount()` 永远包含这个 stale 条目，
  `ScriptExecutor.startProcess` 的 slot 检查会一直 `>= maxConcurrent`，最终所有执行被拒。
- **修复方向**：try/finally 包住 `process.waitFor` 与 `drainAsync.join`；finally 内：
  1. `registry.unregister(executionId)`
  2. 若 process 仍在跑（IllegalThreadStateException），`destroyForcibly` + 5s waitFor

### P0-2：`ScriptExecutor.buildKinitWrappedCommand` 写入 wrapper 但不清理

- **位置**：`executor/ScriptExecutor.java:485-501`
- **规则依据**：`code-review/cr-resource-leak.md` + `code-review/cr-security.md` §"Files left behind expose contents"
- **问题**：`_kinit_wrap.sh` 写入 `executionDir/_kinit_wrap.sh`，**正文包含 `keytab` 完整路径与 `principal`**。
  执行失败 / 取消时不会被删除；执行成功的也只在 Cleanup 阶段随 executionDir 一起被清。
  任何读取 executionDir 的途径（包括历史查看接口、`readStdout`/`readStderr` 周边工具）都可能看到 keytab 路径。
- **修复方向**：3 种思路选 1：
  1. **进程内 export KRB5CCNAME/KRB5_KTNAME**：免写 wrapper；前提：Kerberos 客户端能识别环境变量（多数 MIT krb5 / Heimdal 支持）。
  2. **wrapper 写完 chmod 600**，并在 `finalizeExecution` 末尾 `Files.deleteIfExists(wrapper)`（带 finally）。
  3. **wrapper 内容只写到进程 fd（ProcessBuilder.Redirect.PIPE）**，不落盘。最稳。

### P0-3：`ScriptExecutor.rerunFromSnapshot` 临时改写原始脚本文件

- **位置**：`executor/ScriptExecutor.java:741-770`
- **规则依据**：`code-review/cr-concurrency.md` §"Concurrent modification" + 业务安全
- **问题**：快照重放在 `Files.writeString(original, body)` 与 `Files.writeString(original, originalBody)`
  之间持有原文件路径，并发读 / 并发执行的请求会看到"快照体"被当成"当前体"。
- **修复方向**：写入 `<executionDir>/snapshot_<execId>.sh`，命令指向此临时路径；执行结束清理。

### P0-4：`BatchService.runParallel` 的 `f.get()` 吞所有异常

- **位置**：`service/BatchService.java:159-161`
- **规则依据**：`core/java-exception-handling.md` §"Do not log and rethrow at every layer"
- **问题**：
  ```java
  for (Future<?> f : futures) {
      try { f.get(); } catch (Exception ignored) {}
  }
  ```
  单行执行异常被无声丢弃；`InterruptedException` 没恢复线程中断状态。
  调用方拿到的 `BatchSummary` 只有 `failed` 计数，没有失败原因 — 排障极难。
- **修复方向**：把异常 message 收集到 `BatchSummary.failures: List<String>`；`InterruptedException` 走 `Thread.currentThread().interrupt()` + `break`。

### P0-5：`CleanupExecutor.deleteRecursively` 排序不可靠

- **位置**：`service/CleanupExecutor.java:230-236`
- **规则依据**：`code-review/cr-anti-patterns.md` §"Premature optimization / 'clever' code"
- **问题**：用 `sorted((a,b)->b.toString().length()-a.toString().length())` 按"字符串长度"
  倒序删除，**不可靠**：例如目录 `/a/longname` 与 `/a2/xx` 字符串长度相近时，父目录可能先被删。
  子目录删除失败被 `try{...}catch(IOException ignored){}` 吞掉，失败计数失真。
- **修复方向**：用 `Files.walk` 后 + 显式 post-order visitor（`Files.walkFileTree` + `SimpleFileVisitor`），
  或用 `Comparator.comparingInt(p -> p.getNameCount()).reversed()`。

### P0-6：`CleanupService.directorySize` 用 `Files.walk` 不限制跟随链接

- **位置**：`service/CleanupService.java:283-293`
- **规则依据**：`code-review/cr-resource-leak.md` §"`Files.lines(...)` used without try-with-resources"
- **问题**：cleanup 路径下若有人放了软链环，`Files.walk` 会 `FileSystemLoopException`。
  当前 try/catch 静默吞掉 → 返回 total=0 → 报告"释放 0 字节"误导运维。
- **修复方向**：传 `LinkOption.NOFOLLOW_LINKS` + `FileVisitOption.DETECT_FILE_LOOPS`；
  在循环内单独 try/catch 不要吞循环异常。

### P0-7：`ScriptExecutor.execute` 在 `attachMdc` 之前打日志可能缺 MDC

- **位置**：`executor/ScriptExecutor.java:108-135`
- **规则依据**：`/java-spring:java-logging` §"MDC"
- **问题**：`prepareContext` 内打日志（如 `log.info("file promotion ...")`，如果有）时 `executionId` MDC 还没设置。
  `scriptbox.log` 行里 `executionId` 字段缺失 → `grep executionId=12345` 漏掉。
- **修复方向**：`attachMdc` 移到 `prepareContext` 末尾（已经有 executionId 之后）；或者提前定义 executionId（合并 H1）。

---

## 2. P1 — 稳定性问题

> 资源泄漏、并发竞态、超时/取消语义错乱等。生产环境长时间运行才会暴露。

### P1-1：`ProcessRunner.drainAsync` 异常被吞

- **位置**：`executor/ProcessRunner.java:136-154`
- **规则依据**：`core/java-exception-handling.md` §"Never swallow exceptions silently"
- **问题**：`catch (IOException e) { log.warn("drain {} 失败: {}", label, e.getMessage()); }` ——
  只写日志不传任何信号给上游。如果 stdout 文件路径不可写，shell 仍在跑但前端看不到任何输出。
  `ProcessResult` 没有 drain 状态字段。
- **修复方向**：增加 `drainFailed: boolean` 到 `ProcessResult`；`finalizeExecution` 据此在 history 写一行 WARN。

### P1-2：`ProcessRunner.run` 异常路径下 process 流未关闭

- **位置**：`executor/ProcessRunner.java:79-86`
- **规则依据**：`code-review/cr-resource-leak.md` §"Stream/Reader never closed on exception path"
- **问题**：`process.getInputStream()` 与 `process.getErrorStream()` 在 try-with-resources 之外；
  若 `process.waitFor` 抛 `InterruptedException`，这两个流未显式关闭 → FD 泄漏。
  实际上 Java 17 Process 在 destroyForcibly 时会关闭它们（隐式），但显式关闭是更稳的做法。
- **修复方向**：把 `process.getInputStream()` / `getErrorStream()` 包到 try-with-resources；
  drain 线程读流后 EOF 自动 close。

### P1-3：`ScriptExecutor.execute` 在 `captureSnapshot` 之前调用 `startProcess`

- **位置**：`executor/ScriptExecutor.java:119-126`
- **规则依据**：`spring-boot/sb-mybatis-plus.md` §"`@Transactional` still applies" + 事务边界
- **问题**：`@Transactional` 没用到此处，但隐含的事务边界问题值得提示：
  `captureSnapshot` → `historyMapper.selectById` → `historyMapper.insert/updateById` → 后续 `startProcess` → `finalizeExecution` →
  再 `updateById(history)`。每个 mapper 调用自管事务。整体不在一个事务内，但**单次执行的 history 行生命
  周期跨越多次 DB 调用**。异常路径下 DB 行 / 磁盘状态可能不一致（例如：写完 RUNNING 行后进程崩了，
  history 卡在 RUNNING）。属于设计问题，需要单独决策（异步持久化 / 状态机）。
- **修复方向**：本阶段暂不动；记录到 P2 跟踪。

### P1-4：`RunningExecutionRegistry.cancel` 与 `ProcessRunner` 双重 destroyForcibly

- **位置**：`service/RunningExecutionRegistry.java:96-112` + `executor/ProcessRunner.java:91-99`
- **规则依据**：`code-review/cr-concurrency.md` §"Acquire locks in a consistent global order"
- **问题**：cancel API 在 `registry.cancel()` 中已经 `descendants().forEach(destroyForcibly)` + `process.destroyForcibly()`；
  `ProcessRunner.run` 在 waitFor 超时时也 `process.destroyForcibly()`。两条路径相互不感知，可能在毫秒级
  并发触发。实际后果是 destroyForcibly 二次调用是幂等的，但日志会出现重复行。
- **修复方向**：单一职责；让 `ProcessRunner` 检测 `cancelled` 标志后不再自己 destroyForcibly，统一交给
  `RunningExecutionRegistry.cancel`。或者反过来：让 `cancel` 只设标志，由 `ProcessRunner` 自己 destroy。

### P1-5：`BatchService.runParallel` 线程池在 submit 前构造，未在异常路径保证关闭

- **位置**：`service/BatchService.java:119-162`
- **规则依据**：`code-review/cr-concurrency.md` §"`ExecutorService` never shut down — resource leak"
- **问题**：`pool = Executors.newFixedThreadPool(...)` 之后 `futures.add(pool.submit(...))`；
  若 `pool.submit` 本身抛 `RejectedExecutionException`（极小概率，但 shutdown 已 done 时可能），
  `pool.shutdownNow()` 仍会执行 — 但**没有任何 try/finally**。如果中间代码抛 OOM 等 fatal 异常，
  线程池不会被关闭。
- **修复方向**：try/finally 包住整个循环。

### P1-6：`ScriptExecutor.execute` `prepareContext` 抛异常时 MDC 不清理

- **位置**：`executor/ScriptExecutor.java:108-135`
- **规则依据**：`code-review/cr-concurrency.md` §"ThreadLocal.set() without a matching finally remove()"
- **问题**：`MDC.put("executionId", ...)` 在 `attachMdc` 中；但 `attachMdc` 在 `prepareContext` 之后调用，
  所以 prepareContext 内的 MDC 风险不大（key 不存在也不会泄漏）。**但是**：
  `prepareContext` 末尾 `MDC.put("batchId", ...)` / `MDC.put("scenarioId", ...)` 在 execute 的 finally 里被
  `MDC.remove("batchId")` / `MDC.remove("scenarioId")` —— 如果 execute 之前的链路里已经有这两个 key（不可能，但
  防御性不足），finally 也会 remove 掉。
- **修复方向**：使用 `MDCCloseable`（SLF4J 提供）或 `try-with-resources` 风格的 `MDC.putCloseable`；
  或者干脆把这 5 个键用一个 sub-MDC 包装，自己持有 / 清理。

### P1-7：`@Transactional` 自调用代理绕过风险（当前未用，但项目未来可能）

- **位置**：所有 Service
- **规则依据**：`spring-boot/sb-mybatis-plus.md` §"`@Transactional` still applies" +
  `code-review/cr-concurrency.md` §"`@Transactional` proxy bypass"
- **现状**：项目目前**没有** `@Transactional` 注解 — 所有 mapper 调用都自管事务。OK。
- **建议**：如果未来在 Service 上加 `@Transactional`，需注意 `this.method()` 形式的内部调用会绕过代理。
  推荐将"事务边界"放 Controller → Facade → 子 Service（每个子 Service 自己管事务），避免大事务覆盖 Process。

### P1-8：`ScriptExecutor.execute` 把事务相关 DB 调用与 Shell 执行交叉

- **位置**：`executor/ScriptExecutor.java:120-127`（`captureSnapshot` insert DB + `startProcess` 启动 shell + `finalizeExecution` update DB）
- **规则依据**：`code-review/cr-concurrency.md` §"@Transactional proxy bypass" + 用户要求
  **"Shell 执行全过程不能处于数据库事务中"**
- **现状**：没有 `@Transactional`，单次 mapper 调用是单独事务。OK。
- **建议**：保持现状；如果未来加事务，**严格禁止**将 `process.waitFor(timeout)` 包含进 `@Transactional`。

---

## 3. P2 — 可维护性问题

> God Service / 重复代码 / 超长方法 / 职责混乱 / 设计模式适用但未使用。
> 这类问题在用户层面"看不到"，但会让后续迭代变慢。

### P2-1：11 个 Service 用字段注入（@Autowired）

- **位置**：`ScriptExecutor`, `BatchService`, `ScenarioService`, `ArtifactService`, `CleanupService`,
  `HistoryService`, `PresetService`, `ScriptTemplateService`, `ScriptVersionService`, `TenantService`,
  `ResultParserService`, `GlobalVariableService`, `SyntaxCheckService`
- **规则依据**：`code-review/cr-anti-patterns.md` §"Mutable static state" + `spring-boot/sb-dependency-injection.md`
- **问题**：
  1. 测试 mock 不便（`@SpringBootTest` 真实 bean 才 OK，Mockito 单测不能替换依赖）
  2. 循环依赖风险（构造器注入会在启动阶段失败，字段注入会在首次使用时 NPE）
  3. 类无法被 final / 不可变
- **修复方向**：把所有 `@Autowired` 字段改成 `private final` + 构造器注入。Lombok 可加 `@RequiredArgsConstructor`。
  注：项目目前用 Lombok 没问题（`ScriptExecutor` 已用构造器注入 + `@Autowired` 字段混合）。

### P2-2：`ScriptExecutor.execute` 主流程 ~28 行

- **位置**：`executor/ScriptExecutor.java:108-135`
- **规则依据**：`/java-core:java-review` §"Code smells — Methods longer than 20 lines"
- **修复方向**：抽 `runPrecheckThenSnapshot(ctx)`；`execute` 缩到 ~10 行。

### P2-3：`ScriptExecutor` `prepareContext` ~100 行

- **位置**：`executor/ScriptExecutor.java:140-237`
- **规则依据**：`/java-core:java-review` §"Code smells — Methods longer than 20 lines"
- **修复方向**：拆 5 个私有方法：`loadScriptAndTenant` / `validateRiskLevel` / `enforceConcurrentGuard` /
  `resolveAndCoerceParams` / `promoteFileInputs` / `buildExecutionContext`。

### P2-4：`ScriptExecutor.preview` ~55 行；`rerunFromSnapshot` ~30 行

- **位置**：`executor/ScriptExecutor.java:638-693`, `741-770`
- **规则依据**：同上
- **修复方向**：抽 `resolveScriptTenantParams`（与 P2-3 共享），`preview` 只负责 "build preview payload"。

### P2-5：`CleanupService.preview` ~140 行

- **位置**：`service/CleanupService.java:109-249`
- **规则依据**：同上
- **修复方向**：抽 4 个私有方法 `previewExecutionDirs`, `previewHistories`, `previewArtifacts`, `previewLogs`。

### P2-6：`ExecutionController` 13 个端点堆在一个 Controller

- **位置**：`controller/ExecutionController.java`（270 行）
- **规则依据**：`spring-boot/sb-project-structure.md`（一般规则）+ `/java-core:java-api-review`
- **修复方向**：拆为 3 个 Controller：`ExecutionRunController`（run/preview/rerun/cancel/state）+ `ExecutionLogController`
  （stdout/stderr/result）+ `ExecutionArtifactController`（list/download）。保持 URL 一致，不影响前端契约。

### P2-7：`ScenarioService.RunResult` / `BatchService.BatchSummary` 等非 record

- **位置**：`service/ScenarioService.java:162-194`, `service/BatchService.java:43-49`
- **规则依据**：`/java-core:java-design-pattern` §"records for value objects" + Java 17
- **修复方向**：Java 17 已稳定使用 record。改为 record 后自动生成 `equals/hashCode/toString`，
  以及不可变语义。可后续跟进。

### P2-8：`ScriptExecutor.preview` / `execute` / `rerunFromSnapshot` 重复参数合并 + 校验逻辑

- **位置**：`executor/ScriptExecutor.java:174-184`, `646-655`, `741-770`
- **规则依据**：`code-review/cr-anti-patterns.md` §"Premature optimization / 'clever' code"（不算违反，但违反 DRY）
- **修复方向**：抽 `private Map<String,String> resolveParams(ExecutionRequest req)`，三处复用。

### P2-9：`ExecutionController.run` 等多处手写 `try/catch`

- **位置**：`controller/ExecutionController.java:48-56`, `64-73`, `171-180`
- **规则依据**：`spring-boot/sb-exception-handling.md` §"Incorrect — Catching in the controller"
- **问题**：已有 `GlobalExceptionHandler.handleIllegalArgument` 与 `handleIllegalState`；Controller 自己 catch 是重复翻译。
- **修复方向**：删除 Controller 内 try/catch；让异常直通 `GlobalExceptionHandler`。

### P2-10：`CleanupExecutor.deleteRecursively` + `CleanupService.directorySize` 重复实现"递归遍历 / 累计大小 / 跳过软链接"

- **位置**：`service/CleanupExecutor.java:217-236` + `service/CleanupService.java:283-293`
- **规则依据**：DRY
- **修复方向**：抽 `private static long directorySize(Path)` 到 `StoragePathService`（或新建 `FileSystemUtils`）。

### P2-11：`map` 类型返回值散落

- **位置**：`ScriptExecutor.preview`, `PrecheckService.run`, `ExecutionController.active` 等
- **规则依据**：`/java-core:java-api-review` §"Flag `@RequestBody Map<String, Object>` — use typed DTOs instead"
- **修复方向**：逐步替换为 record DTO；本阶段不改。

### P2-12：`MyBatis-Plus` 多处用 `QueryWrapper` 字符串列名

- **位置**：`CleanupService.java:169`, `194`, `ArtifactService.java:100`, `159`, `CleanupExecutor.java:157` 等
- **规则依据**：`spring-boot/sb-mybatis-plus.md` §"Prefer LambdaQueryWrapper over string columns"
- **修复方向**：改用 `LambdaQueryWrapper<>` 重构；本阶段不改（侵入较大）。

---

## 4. P3 — 代码风格 / 文档 / 日志

### P3-1：方法命名一致性

- `buildEnv` vs `setupEnv` vs `prepareEnv`（`ScriptExecutor`）
- `computeResult`（`CleanupExecutor`）vs `summary()`（`BatchService`）同义不同名
- **修复方向**：选一个项目级约定（推荐 `build*` / `summary()` / `compute*`）。

### P3-2：Lombok `@Slf4j` 未引入

- **现状**：项目用 `private static final Logger log = LoggerFactory.getLogger(...)` 手写，11+ 个类。
- **规则依据**：`/java-spring:java-logging` §"Lombok @Slf4j annotation usage"（推荐 Lombok 时用）
- **修复方向**：检查 `pom.xml` Lombok 是否存在；存在则批量替换；不存在保持手写。

### P3-3：`Map` 字段返回 `Map<String, Object>` 缺少泛型 / nullability

- **位置**：`PrecheckService.run` 返回 `Map<String, Object>` —— `ok` / `message` 字段约定散落在调用方。
- **修复方向**：抽 `PrecheckSummary` record。

### P3-4：缺 `Optional` 替代裸 null 返回

- **现状**：`Mapper.selectById(...)` 直接返回 `null`，Service 层用 `if (x == null) throw ...` 处理 —— 与项目风格一致，OK。
- **建议**：保留现状；如果未来引入 `Optional` 风格，仅限 `findXxx` 类命名。

### P3-5：`@Controller` 的 `Logger` 字段位置不一致

- **位置**：`ExecutionController.java:268-269` 把 `log` 放在文件末尾；其他 Controller 顶部。
- **修复方向**：统一放到类顶部，紧跟字段声明。

### P3-6：常量散落

- **位置**：`ProcessRunner.DRAIN_JOIN_MS`, `CANCEL_WAIT_SECONDS`；`BatchService.MAX_ROWS`；`FileUploadService.SAFE_NAME` 等
- **建议**：保持现状（已经是 private static final）。

### P3-7：中文日志漏写（部分场景）

- **要求**：用户给出的中文日志模板
  - "开始执行脚本，executionId={}，script={}，tenant={}" — 已写（`ScriptExecutor.execute`）
  - "脚本参数准备完成，executionId={}，参数数量={}" — **未写**
  - "开始执行前置检查，executionId={}" — 已写（`runPrecheckOrRecordFailure` 起始）
  - "执行前置检查通过，executionId={}" — 已写
  - "执行前置检查失败，executionId={}，原因={}" — 已写（用 WARN）
  - "Shell进程启动成功，executionId={}，pid={}" — **未写**（ProcessRunner 没打）
  - "脚本执行成功，executionId={}，耗时={}ms" — **未写**（只在失败 / 超时 / 取消打日志，成功路径无 INFO）
  - "脚本执行失败，executionId={}，原因={}" — 已写
  - "脚本执行超时，executionId={}，超时时间={}秒" — 已写
  - "用户取消任务，executionId={}" — 已写
  - "租户测试开始，tenant={}" — **未写**（无 tenant 测试端点）
  - "Kerberos认证成功，tenant={}" — **未写**（KerberosPrecheckStrategy 里有，但格式不同）
  - "Kerberos认证失败，tenant={}，原因={}" — 已写
  - "场景执行开始，scenario={}" — 已写（带 scenarioId）
  - "场景步骤执行失败，scenario={}，step={}，原因={}" — 已写
  - "批量执行开始，batchId={}，tenantCount={}" — **未写** tenantCount（BatchService 只写 rows，不写 tenantCount）
  - "批量执行完成，batchId={}，成功={}，失败={}" — 已写
  - "执行产物扫描完成，executionId={}，文件数量={}，总大小={}字节" — 部分写（只写文件数）
  - "开始生成手动清理预览" — 未显式写（在 `preview` 入口前补一句）
  - "手动清理完成，删除={}，跳过={}，失败={}，释放={}字节" — 部分写（缺跳过 / 失败计数）

### P3-8：日志中 token / password / keytab 字段未严格脱敏

- **现状**：`SensitiveDataMasker.maskCommandList` 遮盖 `--password` / `--token` 等命令行参数（OK）。
- **风险**：如果有日志直接 `log.info("env: {}", env)`，`env` 里可能有敏感 GlobalVariable；当前 `maskEnv` 仅在
  snapshot / preview 路径调，主流程日志没经过脱敏。
- **修复方向**：在 `buildEnv` 之后 / 之前打日志用 `maskEnv` 输出；同时给 `SensitiveDataMasker` 加
  `maskEnvAndLog` 这种薄封装。

### P3-9：执行成功路径无 INFO 日志

- **位置**：`ScriptExecutor.finalizeExecution` 当 `ok=true` 时只打 result.json / artifact 解析日志，缺一行"成功"
- **修复方向**：加 `log.info("脚本执行成功 executionId={} 耗时={}ms", ctx.executionId(), duration)`。

### P3-10：`ExecutionController.cancel` 的 `log.info` 无 MDC

- **位置**：`controller/ExecutionController.java:108`, `125`
- **修复方向**：请求开始时 `MDC.put("executionId", id)`（try-finally remove），或者仅依赖 `RequestMdcFilter`
  已有机制（看 `RequestMdcFilter` 实现）。

### P3-11：`GlobalExceptionHandler.handleBusiness` 的 `log.warn` 没带 code / message 之外的诊断信息

- **位置**：`exception/GlobalExceptionHandler.java:38-43`
- **修复方向**：补 `ex.getCause()` 链 + request URI（在 advice 里通过 `WebRequest` 拿）。

---

## 5. 已被本轮检查确认不属于问题的项

- ✅ 不存在 `printStackTrace`（grep 过 `System.err.print` / `printStackTrace`）
- ✅ 不存在 `bash -c` + 用户输入拼接（README 已声明，代码内 `ProcessBuilder(List<String>)`）
- ✅ 不存在 `String` 拼接进 SQL（全部 MyBatis-Plus Wrapper）
- ✅ 不存在 `RuntimeException` 滥用（业务异常走 `BusinessException`；其他为 Java 标准异常）
- ✅ 不存在 `synchronized` 整方法（仅有局部 `synchronized (summary.historyIds)` 用于并发 batch 汇总）
- ✅ 不存在显式 `ThreadLocal`（无 thread-pool 上下文泄漏风险）
- ✅ 不存在 `finalize` / `Object.finalize` 清理资源
- ✅ `Thread.setDaemon(true)` 在 drain / batch 池都用上（OK）
- ✅ `Files.list` / `Files.walk` / `Files.lines` 都用 try-with-resources
- ✅ `BufferedReader` / `BufferedWriter` 都 try-with-resources
- ✅ ConcurrentHashMap 用于 `RunningExecutionRegistry.live`（OK）
- ✅ `AtomicBoolean` / `AtomicLong` 用于取消标志与 ID 生成（OK）

---

## 6. 重构优先级总结

| 阶段 | 编号 | 内容 | 风险 |
| --- | --- | --- | --- |
| 第 1 步 | P0-1, P0-2, P1-2 | ProcessRunner 加 try/finally；kinit wrapper 改 in-memory | 低（API 不变） |
| 第 2 步 | P0-3, P0-4 | rerun 改用临时副本；BatchService f.get() 收集异常 | 中（行为更可观察） |
| 第 3 步 | P0-5, P0-6, P1-1 | CleanupExecutor 排序 + walk 修；drain 失败写 history | 低 |
| 第 4 步 | P0-7, P1-6 | MDC attach 提前；MDC 用 try-with-resources | 低 |
| 第 5 步 | P1-3, P1-5, P1-8 | BatchService 线程池 try/finally；事务边界审视 | 低 |
| 第 6 步 | P2-9 | Controller 删除手写 try/catch | 低 |
| 第 7 步 | P2-1 | 全项目切到构造器注入 | 中（涉及大量类） |
| 第 8 步 | P2-2, P2-3, P2-4, P2-5, P2-6 | 大方法拆分；Controller 拆分 | 低 |
| 第 9 步 | P2-10, P2-12 | directorySize 抽公共方法；QueryWrapper → LambdaQueryWrapper | 低 |
| 第 10 步 | P3-7, P3-8, P3-9, P3-10 | 补齐中文日志 + 脱敏 | 低 |
| 第 11 步 | P3-2, P3-5 | Lombok `@Slf4j`（如果已有依赖）；Logger 位置统一 | 低 |
| 第 12 步 | P2-7, P2-11 | 内部 DTO 转 record | 低 |

---

## 7. 后续阶段

- **Phase 3**：SOLID review
- **Phase 4**：Design Pattern review
- **Phase 5**：Concurrency review（已部分嵌入本报告 P1-*)
- **Phase 6**：Logging review（已部分嵌入本报告 P3-7 ~ P3-11）
- **Phase 7**：用 java-development-skill 规则文件（resource-leak / null-safety / exception handling / MyBatis-Plus）做补充 review
- **Phase 8**：按上述 12 步小步重构，每步 `mvn test`
- **Phase 9**：`JAVA_SKILL_REFACTOR_REPORT.md` 汇总

---

报告生成完毕，未改代码。