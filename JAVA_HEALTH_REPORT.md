# JAVA_HEALTH_REPORT.md

> 工具：`/java-core:java-health`（来自 ducpm2303/claude-java-plugins，已 symlink 到 `~/.claude/skills/java-plugins/java-core:java-health`）
> 工具：`java-development-skill`（来自 zander-zyx/java-development-skill，已通过 `install.sh --all` 装到 `~/.claude/skills/java-development`）
> 检查范围：`src/main/java/com/bigdata/scriptbox/**`
> 检查日期：2026-09-12
> 本阶段未做任何代码改动；只产出报告。

---

## 0. 项目元数据

| 项 | 值 |
| --- | --- |
| Java 版本 | 17（pom 中 `<java.version>17</java.version>`） |
| Spring Boot | 3.3.5 |
| 持久层 | MyBatis-Plus 3.5.7 + H2 File（`scriptbox;DB_CLOSE_ON_EXIT=FALSE`） |
| 进程调用 | `java.lang.ProcessBuilder`（无 bash -c 拼接） |
| 控制器数量 | 23 个 `@RestController` |
| 服务数量 | 19 个（不含 precheck 子包） |
| Mapper 数量 | 13 个 BaseMapper |
| 实体数量 | 13 个 `@TableName` |
| 策略数量 | 6 个 `PrecheckStrategy` |
| 总 Java 文件 | 83 个 |

---

## 1. 总评分（A/B/C/D/F）

```
╔══════════════════════════════════════════════════════════════════╗
║                  JAVA HEALTH REPORT                              ║
║  Project: BigData Script Box                                    ║
║  Java: 17   |   Spring Boot: 3.3.5                              ║
╠══════════════════════════════════════════════════════════════════╣
║  Security           22 / 25   [A-]   B（存在少量敏感日志 + bash 包装器写入）    ║
║  Test Coverage      15 / 25   [C]   核心 service 有少量单测，缺并发 / cancel 路径  ║
║  Performance        21 / 25   [A-]   无 N+1，无循环查询；Async drain 实现正确     ║
║  Code Quality       20 / 25   [B+]  大量类有详尽中文 Javadoc，个别类行数偏多        ║
╠══════════════════════════════════════════════════════════════════╣
║  TOTAL HEALTH       78 / 100  [B+]                                       ║
╚══════════════════════════════════════════════════════════════════╝
```

注：本评分遵循 `/java-core:java-health` 的规则（每维 25 分，扣分项按"每发现一处扣 N 分，封顶 M 分"计算）。其中 **Test Coverage** 是结构评分（非真实覆盖率），只反映是否有测试文件 / 异常路径测试 / 不 mock 主类。

---

## 2. 严重问题（Critical，必须先修）

> 这些不会让系统挂掉，但生产运行会出现"看不见"的安全 / 稳定性风险。
> 对应 `/java-core:java-review` Critical 等级；本报告阶段不改代码，列在
> `JAVA_CODE_REVIEW_REPORT.md` 的 P0/P1 节里统一处理。

| 编号 | 主题 | 位置 | 风险概述 |
| --- | --- | --- | --- |
| C1 | 取消未彻底回收资源 | `ProcessRunner.run` | `process.destroyForcibly()` 杀父进程，但 `ProcessHandle.descendants()` 已经在 `RunningExecutionRegistry.cancel` 走了一遍。两者之间存在 race：cancel 在 `waitFor` 之前 / 之后都行，但 destroy 完之后没等子进程真正退出，可能留下孤儿 kinit 进程。 |
| C2 | 注册表未保证 unregister | `ProcessRunner.run` | `registry.register(...)` 后若 `pb.start()` 之后任何代码抛异常（包括 `InterruptedException` 路径），`registry` 都不会 `unregister`。registry 中的 stale 条目会让 `activeCount()` 虚高，阻塞后续 `startProcess` 的 slot 校验，最终所有执行都被拒。 |
| C3 | `kinit_wrap.sh` 临时文件未清理 | `ScriptExecutor.buildKinitWrappedCommand` | wrapper 写到 `executionDir` 下；如果执行失败 wrapper 文件不会自动清，会一直留在执行历史目录里，直到 Cleanup 删除整个 executionDir 才走。在 mock=false 场景下尤其碍眼。 |
| C4 | rerun 修改原始脚本文件 | `ScriptExecutor.rerunFromSnapshot` | 已知 race：rerun 期间 `Files.writeString(original, body)` 会让其他人 `readScriptBody` 看到的是"快照体"而不是"当前体"。文档中标注了"妥协"，但生产上仍可能造成误判。 |
| C5 | BatchService 线程池未在异常路径关闭 | `BatchService.runParallel` | `for (Future<?> f : futures) f.get()` 屏蔽了所有异常（`catch (Exception ignored)`），不会把异常传给上层。`pool.shutdownNow()` 总会调用，但 `InterruptedException` 不会被 `restoreInterrupt()`。 |
| C6 | `cleanup` 的循环里 `f.get()` 吞异常 | `BatchService.runParallel` | 同上 + `f.get()` 的 `InterruptedException` 没恢复线程中断状态。 |
| C7 | `CleanupService.directorySize` 用 `Files.walk` 不限深度 | `CleanupService.directorySize` | 删除嵌套执行目录时可能因为 symlink 环触发 `FileSystemLoopException`。walk 应当 NOFOLLOW_LINKS 或限制深度。 |
| C8 | `deleteRecursively` 排序 + 同 try 块结构脆弱 | `CleanupExecutor.deleteRecursively` | 用 `sorted((a,b)->b.toString().length()-a.toString().length())` 依赖路径字符串长度近似深度排序，无法处理同名目录长度巧合；一旦深度相同且名字等长就可能父目录先被删，子目录 delete 失败但被 catch 吞掉（语义无致命影响，但报告里 written failed count 会变脏）。 |

---

## 3. 高优先级问题（High，应在 Phase 2 review 后立刻修）

| 编号 | 主题 | 位置 | 风险概述 |
| --- | --- | --- | --- |
| H1 | `nextExecutionId()` 调用两次浪费 ID | `ScriptExecutor.prepareContext` | 第一次调用用于 `fileUploadService.promoteForExecution`，第二次才生成真正 ID。两次中间值不可见，但 ID 序列会"跳号"，DB 行 id 出现空档。 |
| H2 | attachMdc 在 prepareContext 之后调用 | `ScriptExecutor.execute` | prepareContext 阶段打的日志没有 executionId MDC（前缀 `cleanup` / `scriptbox.log` 会拿不到关键字段），排障时不便。 |
| H3 | `processRunner.run` 没在 `IOException` 路径 unregister | `ProcessRunner.run` | `pb.start()` 抛 IOException → 已 register 没 unregister。registry 泄漏。 |
| H4 | drain 线程异常被吞 | `ProcessRunner.drainAsync` | `catch (IOException e) { log.warn(...); }` —— 如果是写入目标文件失败（例如权限问题），shell 仍在跑但没人看到日志，需要在历史里标记。 |
| H5 | `nextExecutionId` 用 `AtomicLong(System.currentTimeMillis() * 1000L)` 起步 | `ScriptExecutor` / `BatchService` | 这是 Snowflake-lite；放在内存里 JVM 重启就清空，意味着 ID 不单调。H2 场景下两台实例同时启动会撞 id（H2 file 模式单进程无此问题，但理论要标记）。 |
| H6 | `@Autowired` 字段注入而非构造器注入 | `ScriptExecutor`, `ScenarioService`, `BatchService`, `ArtifactService`, `CleanupService` 等 11 个类 | 与项目自身的 `RestControllerAdvice` / `JacksonConfig` 等新写的 config 类风格不一致；测试不友好；违反 java-development-skill 的 DI 规则。 |
| H7 | `Controller` 里手写 try/catch | `ExecutionController.run`, `ExecutionController.preview`, `ExecutionController.rerun` | 已经有 `GlobalExceptionHandler`，但 Controller 仍然自己 catch `IllegalArgumentException` / `IOException`。重复的异常翻译路径，且 Controller 的 `run()` catch `IllegalArgumentException` 转 `ApiResponse.error(...)` 与 `GlobalExceptionHandler.handleIllegalArgument` 重复，二者都返回 `code=1` 但行为重复。 |
| H8 | `ExecutionController` 用 `logger` 但 `logger` 没 MDC | `ExecutionController` | 用户取消时打 `"用户取消脚本执行，executionId={}"` 没有 MDC；`scriptbox.log` 行里 `executionId` 字段不会有。 |
| H9 | `sha256Hex` 使用 `String.format("%02x", b)` | `ScriptExecutor`, `ArtifactService` | 每次构造 StringBuilder + autoboxing byte→Byte；大文件 N 次；改用 `HexFormat.of()` 或 `String.format("%02x", b & 0xff)` 更高效。属于性能小坑。 |
| H10 | `attachMdc` 之后任何 catch 块都未清理 MDC 字段 | `ScriptExecutor.execute` | `try-finally` 已覆盖 `MDC.remove` 5 个键；但 `attachMdc` 仅设置 3 个（executionId / scriptName / tenantName），batchId / scenarioId 在 `prepareContext` 内单独设置，与 `execute` 末尾的 `finally` 移除的键名一致 — 这一项 OK。 |
| H11 | PrecheckService / PrecheckStrategy 对 tenant 不可用时未 fail-fast | `PrecheckService.run` | 如果 `cfg` 缺失 `kerberos` 但租户实际需要（生产部署），不会触发 Kerberos 验证，只能靠运行时报错。 |

---

## 4. 中优先级问题（Medium，建议在 medium-size 重构时处理）

| 编号 | 主题 | 位置 | 风险概述 |
| --- | --- | --- | --- |
| M1 | `ScriptService` / `GlobalVariableService` 等"胖服务" | 多个 Service | 文件数量增加后，单 Service 难以一眼看清职责；建议后续按业务域拆分（ScriptDomain / TenantDomain / ExecutionDomain）。当前不算严重，但已经是 SRP 警戒线。 |
| M2 | `ExecutionController` 的 13 个端点堆在一个 Controller | `ExecutionController` | 单 Controller 行数偏多（270 行），按 REST 风格拆为 `ExecutionRunController` / `ExecutionQueryController` / `ExecutionArtifactController` 更清晰。但当前内聚性 OK，可不改。 |
| M3 | `ScenarioService.RunResult` / `RunEntry` / `BatchService.BatchSummary` 等内嵌 DTO | `ScenarioService`, `BatchService` | 内嵌类放大了"主类"的行数，也容易出现循环依赖。提取为独立 `model` / `dto` 类更清爽。 |
| M4 | `Map<>` 返回值散落在多处 | `ScriptExecutor`, `CleanupService`, `ExecutionController` | 类型不安全；建议引入 record DTO 取代裸 Map（但这是侵入式重构，可在 Phase 2 评审后再决定）。 |
| M5 | 部分 Service 缺少接口 | `ScriptExecutor`, `RunningExecutionRegistry`, `CleanupService` 等 | 仅"实现类"无 `*Service` 接口；测试场景里 mock 不便。当前测试用 `@SpringBootTest` + 真实 bean，OK。但如果走 Mockito 单测会卡。 |
| M6 | `ExecutionContext.Builder` 12 个 setter | `ExecutionContext` | Builder 是为了解决"参数多"问题，本身不丑，但 12 字段统一 setter 是数据类的反模式 — 后续若再加字段，构造函数将变很长。考虑迁移到 `record + wither` 模式（Java 17 友好）。 |
| M7 | `GlobalVariableService`, `TenantService` 等采用 field injection | 同 H6 | 与 H6 重复但关注点不同：M7 关注"测试时无法注入 mock"。 |
| M8 | `preview` / `rerunFromSnapshot` / `execute` 内重复代码 | `ScriptExecutor` | 三处都做 "preset < supplied" 参数合并 + validateAndCoerce + 拿脚本 / 租户。可抽出 `prepareValidatedContext(req)`。属于代码坏味道（DRY），不影响功能。 |
| M9 | 多个 Controller 用裸 `Autowired` 字段 | `ExecutionController` 等 23 个 | 与 H6 同源。 |
| M10 | `mvn test` 不覆盖 `mvn package` 的前端构建 | 缺 | 当前 `mvn package` 会触发 npm 构建。单测可以 `-DskipFrontend=true` 跳过，但完整回归需要 `mvn clean package`。 |

---

## 5. 低优先级问题（Low / Style）

| 编号 | 主题 | 位置 |
| --- | --- | --- |
| L1 | `PreviewStore.CleanupPreview.controlledPaths.executionsRoot` 用 String 而非 Path | `PreviewStore` |
| L2 | `BatchSummary`, `RunResult`, `RunEntry` 等非 record | `BatchService`, `ScenarioService` |
| L3 | `int`, `long`, `boolean` 等原始类型在 DTO 中散落 | 多处 |
| L4 | `// 设置名称` 类的"翻译型"注释已清理 | 之前 commit `ad14edd` 已全面中文化；目前无遗留 |
| L5 | 命名一致性：`buildEnv` vs `setupEnv` vs `prepareEnv` | `ScriptExecutor` |
| L6 | `ScriptExecutor.execute` 主流程仍 ~28 行（>20 阈值） | `ScriptExecutor` |
| L7 | `ScriptExecutor.preview` ~55 行 | `ScriptExecutor` |
| L8 | `CleanupService.preview` ~140 行 | `CleanupService` |
| L9 | `resultParserService.readStructured` 内联解析逻辑 | `ResultParserService`（未读全，怀疑） |
| L10 | `ScriptController` / `TenantController` 等 Controller 行数 / 端点分布不均 | 23 个 Controller |

---

## 6. Test Coverage（结构评分）

| 维度 | 现状 | 建议 |
| --- | --- | --- |
| 已写测试类 | 4 个（`ExecutionContextTest`, `SensitiveDataMaskerTest`, `StoragePathServiceTest`, `ProcessRunnerTest`） | 覆盖到核心执行路径 |
| 缺：并发 / Cancel 路径 | `ProcessRunnerTest` 仅有 happy path / timeout / cancelled 三种，未覆盖"cancel 后子进程残留" | 加 `ProcessRunnerCancellationIT` |
| 缺：异常路径 | `ScriptExecutor.execute` 缺"参数校验失败抛 IllegalArgumentException" / "快照缺失抛 IllegalStateException" 等 | 加 `ScriptExecutorValidationTest` |
| 缺：Cleanup 真实删除 | `CleanupExecutor` 没有 IT 跑通端到端（文件系统 + DB） | 加 `CleanupExecutorIT` |
| 缺：API 契约 | 23 个 Controller 均无 `@WebMvcTest` 切片测试 | 增加 `ExecutionControllerTest` 等 |
| 缺：integration | 仅有单测；缺 mock 全栈（MyBatis-Plus + H2 file + Tomcat） | 加 `ApplicationIT` |

注：`/java-core:java-health` 的 Test 评分里提到："Tests that mock the class under test → -4"。本项目所有 Service 测试都是用 `@SpringBootTest` + 真实 H2，未 mock 主类，OK。

---

## 7. Performance 评分

| 检查项 | 命中 |
| --- | --- |
| `@OneToMany` 未设 LAZY | 不适用（项目用 MyBatis-Plus，无 JPA） |
| N+1 循环内 repository 调用 | 未发现（`ArtifactService.scanAndRegister` 用 `Files.list` + sort 而非循环 DB 查询） |
| `findAll()` 无分页 | 多处使用 `selectList(null)`，但都是后台管理类查询（`listAll` / `summarize`），数据量小。OK。 |
| 字符串拼接在循环里 | `String.format("%02x", b)`（H9 已记） |
| `synchronized` 整方法 | 未发现 |

---

## 8. Security 评分

| 检查项 | 命中 |
| --- | --- |
| 硬编码 secret | 未发现（`SensitiveDataMasker` + `maskCommandList` 已实现） |
| SQL 字符串拼接 | 未发现（全部 MyBatis-Plus Wrapper） |
| Controller 缺 `@Valid` | `ExecutionRequest` 未标注 `@Valid`，参数全靠 Service 内 validateAndCoerce。属于设计选择（前后端契约），但 Spring Boot 6+ 推荐 `@Valid`。 |
| 弱哈希 | 使用 SHA-256（正确） |
| `response body` 内含敏感信息 | 已用 `SensitiveDataMasker.maskCommandList` 在日志层脱敏；preview 接口把 `keytabPath` 替换为 `MASK` 常量（正确）。 |
| 不安全反序列化 | `mapper.readValue(...)` 都是带 TypeReference 的，无 `enableDefaultTyping`（正确）。 |
| 路径穿越 | `StoragePathService.assertInside` / `isInsideReal` / `ArtifactService.normaliseName` / `FileUploadService.SAFE_NAME` 多层防御（强）。 |
| 命令注入 | `ProcessBuilder(List<String>)` + 显式禁止 `bash -c`（README 中已声明）。 |
| SSRF | 项目无 outbound HTTP；N/A。 |
| Zip Slip | 暂未处理 zip 上传；当前无此接口。 |
| Unsafe 临时文件 | `FileUploadService.savePending` 用 `UUID.randomUUID()` + 安全目录（OK）；`kinit_wrap.sh` 写入 `executionDir`（C3 提了未清理）。 |

---

## 9. 关键建议（按 P0 → P3 排序）

| P0（安全 / 数据风险） | 处理建议 |
| --- | --- |
| C1, C2, C3, C4 | `ProcessRunner` 加 try/finally 包裹 registry.unregister；destroyForcibly 改为先 children 后 parent，并 `waitFor` 至 5s；删除 / 不写 kinit_wrap（改为进程内 export）；rerunFromSnapshot 改用临时副本不污染原文件 |
| C5, C6, C7, C8 | BatchService 修 InterruptedException 处理 + 异常保留 cause；CleanupService.directorySize 加 NOFOLLOW_LINKS；CleanupExecutor.deleteRecursively 改用 Files.walk + post-order visitor |

| P1（稳定性 / 资源） | 处理建议 |
| --- | --- |
| H1, H2, H3, H4, H10 | ScriptExecutor.prepareContext 把 MDC attach 提前到 prepareContext 内；H1 把 ID 生成合并到一处；ProcessRunner drain 失败要把异常写入 history summary |
| H6, H7 | 11 个 Service + 23 个 Controller 改用构造器注入；Controller 删除手写 try/catch，由 GlobalExceptionHandler 统一处理 |
| H11 | PrecheckService 强制读 `kerberos` 字段若 tenant 标了 `enabled=true` 且是 mock=false |

| P2（可维护性） | 处理建议 |
| --- | --- |
| M1, M3, M5, M6, M8 | 按业务域拆分（后续工作）；DTO 改 record；ScriptExecutor 抽 `prepareValidatedContext` |
| M9 | 全项目切换到构造器注入 |

| P3（风格 / 文档） | 处理建议 |
| --- | --- |
| L4, L5, L6, L7, L8 | 方法过长时拆分；命名统一 |

---

## 10. 已确认"非问题"

下列检查项已审过但不属于问题：

- ✅ 项目使用 MyBatis-Plus 3.5.7 而非 JPA（与 README 一致，不切换）
- ✅ Spring Boot 3.3.5，Java 17（不升级）
- ✅ `ProcessBuilder(List<String>)` 形式，命令参数不拼接字符串
- ✅ 日志走 SLF4J，无 `System.out.println`（除 StartupLogger）
- ✅ 测试不使用 mock 主类（用 @SpringBootTest 真实 bean）
- ✅ ConcurrentHashMap 用于 `RunningExecutionRegistry.live`
- ✅ `try-with-resources` 在 Stream / Reader / Writer 处使用（多数 OK；`newBufferedWriter` + `BufferedReader` 都 try-with-resources）
- ✅ `Files.lines` / `Files.list` / `Files.walk` 全部用 try-with-resources
- ✅ 业务日志全部中文（最近一次 commit 已完成）
- ✅ 大部分类都有中文 Javadoc（最近一次 commit 已完成）

---

## 11. 后续阶段

- **Phase 2**：`JAVA_CODE_REVIEW_REPORT.md`（P0/P1/P2/P3）
- **Phase 3**：SOLID review
- **Phase 4**：Design Pattern review
- **Phase 5**：Concurrency review
- **Phase 6**：Logging review
- **Phase 7**：用 java-development-skill 规则文件做补充 review（resource-leak / null-safety / exception handling / MyBatis-Plus）
- **Phase 8**：按 P0 → P1 → P2 → P3 小步重构，每步 `mvn test`
- **Phase 9**：`JAVA_SKILL_REFACTOR_REPORT.md` 汇总

---

报告生成完毕，未改代码。