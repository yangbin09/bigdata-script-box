# BigData Script Box — Java 大型重构评审（结构级）

> 本次**只做分析**：未修改任何 Java 代码，未提交 Git，未新增功能。
> 唯一的工作区改动是评审开始前既已存在的 `pom.xml`（见下文 §1.2）。
> 生成物：本文件 `JAVA_MAJOR_REFACTOR_REVIEW.md`。

- 分析对象：`D:\code\java\shell\demo\bigdata-script-box`（Java 17 / Spring Boot 3.3.5 / Spring MVC / MyBatis-Plus 3.5.7 / H2 / Maven / ProcessBuilder）
- 代码规模：`src/main/java` **94 个文件 / 9,049 行**，`src/test/java` **29 个文件 / 3,555 行**
- 评审依据：逐文件通读 controller / service / executor / config / entity / dto / exception 全部核心类，并实际执行了 `git status` 与 `mvn test`

---

## 1. 实际执行的命令与真实结果

### 1.1 `git status`

```
On branch main
Your branch is up to date with 'origin/main'.

Changes not staged for commit:
	modified:   pom.xml
```

工作区干净，只有 `pom.xml` 一个未暂存改动。

### 1.2 `pom.xml` 的既有改动（非本次产生）

```diff
@@ -57,6 +57,11 @@
             <artifactId>spring-boot-starter-test</artifactId>
             <scope>test</scope>
         </dependency>
+        <dependency>
+            <groupId>org.projectlombok</groupId>
+            <artifactId>lombok</artifactId>
+            <scope>provided</scope>
+        </dependency>
     </dependencies>
```

**发现**：`pom.xml` 中 Lombok 被声明了 **两次**——第 48–53 行（`<optional>true</optional>`）和第 60–64 行（`<scope>provided</scope>`）。这一处是纯噪音，与本次 10 条重构无关，顺手清掉即可。

### 1.3 `mvn test`

环境里 `mvn` 不在 `PATH`（`The term 'mvn' is not recognized`），改用 wrapper dists 中已有的 Maven 3.9.9，并加 `-DskipFrontend`（避免 `exec-maven-plugin` 调用 `npm ci` / `npm run build`）：

```
& "...\.m2\wrapper\dists\apache-maven-3.9.9-bin\...\bin\mvn.cmd" -B -DskipFrontend test
```

**真实结果：`Tests run: 156, Failures: 1, Errors: 131, Skipped: 0` → `BUILD FAILURE`（耗时 1:26）**

逐类结果（来自 `target/surefire-reports/*.txt`）：

| 测试类 | Tests | Errors | 说明 |
|---|---|---|---|
| `ApiSmokeTest` | 1 | 1 | 上下文加载失败 |
| `ArtifactServiceTest` | 6 | 6 | 同上 |
| `BatchServiceTest` | 4 | 4 | 同上 |
| `ConcurrencyGateTest` | 8 | 8 | 同上 |
| `ConditionalParamTest` | 8 | 8 | 同上 |
| `DryRunTest` | 3 | 3 | 同上 |
| `ExecutionCancellationTest` | 8 | 8 | 同上 |
| `ExecutionContextTest` | 4 | 0 | **通过** |
| `ExecutionSnapshotTest` | 7 | 7 | 上下文加载失败 |
| `FileParameterTest` | 3 | 3 | 同上 |
| `GlobalVariableServiceTest` | 6 | 6 | 同上 |
| `H2PersistenceTest` | 1 | 1 | 同上 |
| `PrecheckServiceTest` | 8 | 8 | 同上 |
| `PresetServiceTest` | 6 | 6 | 同上 |
| `ProcessRunnerTest` | 5 | 0 | **通过** |
| `ResultParserServiceTest` | 5 | 5 | 上下文加载失败 |
| `RiskLevelExecutionTest` | 9 | 9 | 同上 |
| `ScenarioServiceTest` | 5 | 5 | 同上 |
| `ScriptExecutorTest` | 5 | 5 | 同上 |
| `ScriptPackageServiceTest` | 5 | 5 | 同上 |
| `ScriptServiceTest` | 2 | 2 | 同上 |
| `ScriptTemplateTest` | 7 | 7 | 同上 |
| `ScriptVersionServiceTest` | 4 | 4 | 同上 |
| `SensitiveDataMaskerTest` | 8 | 0 | **通过** |
| `StoragePathServiceTest` | 8 | 0 (1 Failure) | **真实的平台假定缺陷** |
| `SyntaxCheckTest` | 11 | 11 | 上下文加载失败 |
| `TenantServiceTest` | 3 | 3 | 同上 |
| `UxEndpointsTest` | 6 | 6 | 同上 |
| **合计** | **156** | **131** | 另 1 个 Failure |

**根因（两类，互相独立）：**

**(A) 131 个 Error — 全部是同一个根因：`bash` 在 Windows 上解析到 WSL，Windows 路径被吞掉反斜杠。**

`target/surefire-reports/com.bigdata.scriptbox.ArtifactServiceTest.txt`：

```
Caused by: java.lang.IllegalArgumentException: syntax check failed:
  /bin/bash: C:Users16922AppDataLocalTempscriptbox-syntax-3523785682198218389.sh: No such file or directory
	at com.bigdata.scriptbox.service.ScriptService.create(ScriptService.java:117)
```

在 `C:\Windows\system32\bash.exe`（WSL 转发器）上手动复现：

```
path=C:\Users\16922\AppData\Local\Temp\sbx-probe.sh
bash -n "$f"                              → /bin/bash: C:Users16922AppDataLocalTempsbx-probe.sh: No such file or directory
bash -n ($f -replace '\\','/')            → /bin/bash: C:/Users/16922/AppData/Local/Temp/sbx-probe.sh: No such file or directory
```

即：WSL 把 `C:\Users\...` 变成 `C:Users...`（反斜杠全部消失），**任何** 传给 bash 的 Windows 绝对路径都失效。失败的测试全部要经过 `ScriptService.create()` → `SyntaxCheckService.check()` → `new ProcessBuilder("bash","-n", <Windows 绝对路径>)`（`SyntaxCheckService.java:100`）。爆炸半径被放大到 131 个用例，正是因为 `ScriptService` 把 `bash -n` 设成了**所有写路径的强制关卡**（第 113、151、188 行三处）。

`ProcessRunnerTest` / `SensitiveDataMaskerTest` / `ExecutionContextTest` 能通过，恰恰因为它们用 `bash -c`（无路径参数）或压根不起进程——这反证了根因就是**路径参数**而不是 bash 本身。

**(B) 1 个 Failure — 真实的平台耦合缺陷（与 bash 无关）：**

`StoragePathServiceTest.pathGenerationFollowsConvention`（`StoragePathServiceTest.java:50`）：

```
org.opentest4j.AssertionFailedError: expected: <true> but was: <false>
```

断言 `script.endsWith("scripts/42/script.sh")` 在 Windows 上为假（分隔符是 `\`）。这是**测试对"POSIX 风格路径字符串"的硬编码假定**，生产代码本身没错，但对"部署目标是 Linux"这一前提没有任何显式约束。

> 这两点直接支撑了建议 2（统一进程执行器 + `shell-executable` 可配置）与建议 1（执行上下文收拢）：**当前主流程在本机连一次都跑不起来**。

---

## 2. 最值得做的 10 个 Java 大型重构建议

以下每一条都基于当前真实代码。排序即最终价值排名（§3 汇总表同序）。

---

### 【排名 1】`ScriptExecutor` 编排拆解：把 885 行执行器切成 Pipeline + Context Provider

**1. 重构名称**
`ScriptExecutor` 拆分 —— Execution Pipeline / `ExecutionPreparer` / `CommandBuilder` 三层收敛。

**2. 当前问题**

`ScriptExecutor.java`（815 行代码 / 885 行含注释）一个类同时承担 **10 种职责**：

| 职责 | 真实位置 |
|---|---|
| 脚本/租户状态校验 | `prepareContext` L146–171 |
| Preset 合并 + 参数强转校验 | `prepareContext` L174–186 + `validateAndCoerce` L594–648 |
| 文件参数 promote | `prepareContext` L189–193 与 L228–233（**重复两遍**） |
| 执行目录 / 日志 / result 路径创建 | L195–205 |
| kinit wrapper 生成 | `createKinitWrapper` L548–567 |
| history 行构造 | `newExecutionHistory` L258–293 |
| PreCheck 编排 | `runPrecheckOrRecordFailure` L299–322 |
| 快照 + SHA-256 | `captureSnapshot` L328–363、`sha256Hex` L678–687 |
| 命令与环境拼装 | `buildEnv` / `buildDirectCommand` / `buildKinitWrappedCommand` / `buildArgsFromMap` L511–584 |
| 状态机 + 落库 + 资源清理 | `finalizeExecution` L415–505 |
| Dry-run 预览 | `preview` L726–781 |
| 历史重放 | `rerunFromSnapshot` L831–884 |

具体结构性问题（都可复现）：

1. **`prepareContext` 单方法 112 行（L145–256）**，10+ 个局部变量交叉依赖 ExecutionRequest / Script / Tenant / Path / Map 五种数据类型。
2. **`nextExecutionId()` 在一次执行里被调用 3 次**：L190（临时）、L195（真值）、`rerunFromSnapshot` L843（又一次）。ID 计数空转，且 L190 那次**先在磁盘上建了目录并拷贝了文件**（见建议 4）。
3. **`preview()`（L726–781）把 `prepareContext` 的 preset 合并 + `validateAndCoerce` 整段抄了一遍**（L734–744 vs L176–186），两处必须同步演进：目前 `preview` 里没有 DANGEROUS 校验、没有 enabled 校验，与真实执行语义已经不一致。
4. **`validateAndCoerce`（L594–648）里 5 分支 `switch(type)`** + boolean required 特例（L602–605）+ `visibleWhen` 过滤（L642–643）混在一个方法里。
5. 错误消息**中英文随机混用**：`"脚本不存在"`(L105) 与 `"script not found"`(L728) 表达同一件事。

**3. 涉及哪些实际类**

`executor/ScriptExecutor.java`（主改）、`executor/ExecutionContext.java`、`dto/ExecutionRequest.java`、`service/PresetService.java`、`service/FileUploadService.java`、`service/PrecheckService.java`、`service/StoragePathService.java`、`service/ArtifactService.java`、`service/ResultParserService.java`、`controller/ExecutionController.java`

**4. 建议怎么改**

按阶段切三个新组件，`ScriptExecutor` 退化为不到 200 行的协调者：

- `ExecutionPreparer#prepare(ExecutionRequest) -> ExecutionContext`
  纯规划、**不碰磁盘**（目录创建与文件拷贝下移到执行阶段）。
- `CommandBuilder#build(ExecutionContext) -> List<String>`
  收拢 `buildDirectCommand` / `buildKinitWrappedCommand` / `buildArgsFromMap` / `buildCommandForPreview` 四处几乎相同的 `new ArrayList<>()`。
- `ExecutionFinalizer#finalize(ctx, ProcessResult) -> ExecutionHistory`
  收拢状态决策（L428–445）、result.json 解析、artifact 扫描、registry 注销、落库、wrapper 删除。
- `preview()` 改为 `prepare()` 的只读投影，**共享同一份合并/校验代码**，从根上消灭 L176–186 与 L734–744 的平行实现。

**5. 建议使用什么设计模式**

Template Method + Pipeline（阶段化编排）+ Parameter Object（`ExecutionContext` 已有雏形，需从 15 字段 builder 收窄）+ Strategy（DANGEROUS 校验、kinit 包装各成独立策略）。

**6. 主要收益**

- 直接修复文件重复 promote 与孤儿目录（与建议 4 合并收益）；
- `preview` 与真实执行语义永远一致；
- 执行流程从"读 885 行找线索"变成"看 5 个阶段"；
- 每个阶段可独立做单元测试（当前 `ScriptExecutor` 只能整体集成测）。

**7. 风险：高**
改动落在所有执行路径的唯一收口点，必然大面积触碰 `ScriptExecutorTest` / `BatchServiceTest` / `ScenarioServiceTest` / `ExecutionCancellationTest` / `ExecutionSnapshotTest` / `RiskLevelExecutionTest` / `ConcurrencyGateTest`。

**8. 工作量：XL**（可分批：先只做 `ExecutionPreparer` + `CommandBuilder` = M）

**9. 推荐程度：极高（第一名）**

---

### 【排名 2】统一子进程执行契约 —— 所有 `ProcessBuilder` 必须走同一个 Runner

**1. 重构名称**
全项目子进程入口收敛为 `CommandExecutor`（唯一契约），`ProcessRunner` 成为其唯一实现。

**2. 当前问题**

全项目有 **3 处互相独立的 `ProcessBuilder` 启动**，语义完全不一致：

| 位置 | 超时 | 输出 drain | 进程回收 | artifact/registry |
|---|---|---|---|---|
| `executor/ProcessRunner.java:68-80` | ✅ `waitFor(timeout)` | ✅ 双 daemon 线程 | ✅ `destroyTree` | ✅ 注册 |
| `service/SyntaxCheckService.java:100-111` | ❌ **`p.waitFor()` 无超时** | ⚠️ 单线程读完 stdout（`redirectErrorStream(true)`） | ❌ 无 `finally` destroy | ❌ |
| `controller/TenantController.java:152-168` | ❌ **`p.waitFor()` 无超时**（kinit 与 klist 两次） | ❌ `readAllBytes()` 一次性读 | ❌ 无 destroy | ❌（还是在 Controller 里！） |

直接后果：

- `SyntaxCheckService.tryBashN`：`bash -n` 遇到 `bash` 卡住（例如受污染的 `BASH_ENV`、NFS 上的死挂载、极慢的 WSL 交互）→ **Request 线程永久挂起**。而这个方法是 `ScriptService.create/update/saveScriptBody` 的强制关卡，也是 `/api/scripts/syntax-check` 的入口——**前端"随打随探"每敲一次就起一个无超时子进程**。
- `TenantController.test`：`kinit` 打 KDC 无响应 → Tomcat 线程挂死；`readAllBytes()` 之后才 `waitFor()`，若进程输出超过管道缓冲且未读完，是典型的 `Process` 死锁组合。
- `"bash"` 作为字面量在 **5 处**重复硬编码：`ScriptExecutor.java:521, 571, 753, 785`、`SyntaxCheckService.java:100`；kinit wrapper 内部还硬编码 `#!/usr/bin/env bash`（`ScriptExecutor.java:562`）。项目文档却声明"部署目标是 FusionInsight Linux 节点"——**没有任何配置项能覆盖 shell 路径**。
- 这正是 **131 个测试 Error 的直接放大器**（§1.3A）：把 `bash` 与路径处理收进一个可配置、可替换的执行器后，Windows 下的 `bash -n` 路径问题才有地方一次性解决（例如 `shell-executable` 指向 Git Bash，或在非 POSIX 平台直接走启发式分支）。

**3. 涉及哪些实际类**

`executor/ProcessRunner.java`、`service/SyntaxCheckService.java`、`controller/TenantController.java`、`config/ScriptBoxProperties.java`（新增配置）、`executor/ProcessRequest.java`

**4. 建议怎么改**

1. 定义 `CommandSpec`（record：`List<String> command / Path workDir / Map<String,String> env / int timeoutSeconds / Path stdout / Path stderr / boolean registerWithRegistry`）与 `CommandResult`（`exitCode / timeout / cancelled / durationMs / drainFailed`）。
2. 抽出 `CommandExecutor` 接口；`ProcessRunner` 保留为唯一生产实现，`registerWithRegistry=false` 时不做并发槽位登记（`bash -n` / `kinit` 是内部短命令，不该占用用户执行槽位）。
3. `SyntaxCheckService` 与 `TenantService`（把 `TenantController.test` 的进程逻辑下沉到 Service）**全部改走 `CommandExecutor`**，与脚本执行共享同一套 drain / timeout / destroyTree 实现。
4. `ScriptBoxProperties` 增加 `scriptbox.shell-executable`（默认 `bash`）与 `scriptbox.kinit-executable`；`ScriptExecutor` 的 4 处 `"bash"` 与 `SyntaxCheckService` 的 1 处全部改读配置。
5. 保留 `SyntaxCheckService` 已有的启发式 fallback（`heuristicCheck` L139–209）作为"shell 不可用"的降级路径——它已经是正确的设计，只是当前触发条件写死了。

**5. 建议使用什么设计模式**

Strategy + Adapter（`CommandExecutor` 收口）+ Null Object（shell 不可用时的启发式实现）。

**6. 主要收益**

- 消除两处可挂死 Tomcat 线程的无超时 `waitFor`；
- 子进程生命周期从"3 份实现"变成"1 份实现、3 个调用点"；
- `shell-executable` 可配置后，本机即可跑 `mvn test`（修复 131 Error 的路径）；
- kinit 连通性测试从 Controller 移出，可单元测试。

**7. 风险：中**（`ProcessRunner` 语义若改错会影响 cancel/timeout 判定；`SyntaxCheckService` 改错会让语法校验静默失效）

**8. 工作量：M–L**（接口抽取 M；三处迁移 L）

**9. 推荐程度：极高**

---

### 【排名 3】输出 drain 忠实性与进程残留兜底 —— 流式重定向 + 终止后校验

**1. 重构名称**
`ProcessRunner` 输出通道与进程生命周期加固。

**2. 当前问题**

`ProcessRunner.drainAsync`（L178–198）逐行 `readLine()` 再 `writer.newLine()` 写回：

- **输出不忠实**：原输出的 `\r\n` 被改写成 `\n`；不带换行符的**末行**在多数情况下仍会写入（`readLine` 会返回尾部残余），但**行内 CR 被丢弃**、`\r` 进度条类输出（`hdfs dfs -put`、`spark-submit` 进度）全部塌成一行。日志是排障的一等公民，这里不该做任何转换。
- 每个 drain 线程一个默认缓冲的 `BufferedWriter`，没有任何"单次输出上限"：脚本 `yes` 或 `while true; do echo; done` 会在 `timeoutSeconds` 内把磁盘写满。`readStdout` 的 `props.getMaxLogBytes()`（`ScriptExecutor.java:792`）只**限制读取展示**，不影响落盘。
- `ProcessRunner.run` 的 `finally`（L137–148）`if (process.isAlive()) destroyTree(process)` 是 best-effort 且**不校验结果**：`destroyForcibly()` 之后没有 `waitFor` 确认，也没有 `process.isAlive()` 复查，失败路径完全静默。JVM 被 `kill -9` 时 wrapper 与业务进程组必然残留，且当前**没有启动期孤儿清扫**。
- `readUpTo`（`ScriptExecutor.java:803–821`）在 `len > max` 分支里 `ch.read(buf)` **不检查返回值**：`ByteChannel.read` 允许短读，短读时返回的 `tail` 会带一段未初始化（全 0）字节。
- 子进程继承 JVM 的 stdout/stderr FD；`drainAsync` 失败只置 `drainFailed`（L191），而 `drainFailed` 最终只换来一条 `log.warn`（`ScriptExecutor.java:448-451`），**不落库、不可见**。

**3. 涉及哪些实际类**

`executor/ProcessRunner.java`（主改）、`executor/ProcessResult.java`、`service/RunningExecutionRegistry.java`、`executor/ScriptExecutor.java`、`config/ScriptBoxProperties.java`

**4. 建议怎么改**

1. `pb.redirectOutput(req.stdoutPath().toFile())` / `redirectError(...)`：让**内核直接写文件**，零解码、零转换、零丢失、不占应用内存，同时天然免疫管道缓冲死锁。
2. 若必须保留应用侧处理（如需要同时做 artifact 提取），改为 `FileChannel` + `ByteBuffer` 分块 `transferFrom`，并**显式循环直到 EOF**（正确处理短读）。
3. 引入 `OutputBudget`：单次执行 stdout / stderr 各自字节上限（配置项已有 `max-artifact-total-bytes` 可复用的语义），超限时停止写入并在 history 中标记，避免磁盘被打满。
4. `destroyTree` 之后加一次 `waitFor(CANCEL_WAIT_SECONDS)` 并**复查 `isAlive()`**；仍存活则 `log.error` 并写入 history 的可观测字段（而不是只 warn）。
5. `readUpTo` 改为循环读，修掉短读未初始化字节。
6. `drainFailed` 上浮到 `ExecutionHistory`（新增一个字段或并入 `summary`），让"输出不完整"在 UI 上看得见。

**5. 建议使用什么设计模式**

Resource Acquisition Is Initialization（execution scope 内确定性释放）+ Template Method（`destroyTree` 统一出口）。

**6. 主要收益**

- stdout/stderr 与进程真实输出**逐字节一致**，排障可信度提升；
- 杜绝"脚本打满磁盘"这一类运维事故；
- 修掉 `readUpTo` 的短读缺陷（当前会返回尾部垃圾字节）；
- 进程残留从"希望它死了"变成"验证它死了"。

**7. 风险：中**（改 drain 方式会影响 `ArtifactService.scanAndRegister` 与 `result.json` 的时序假设；需要回归 `handlesLargeOutputWithoutDeadlock` 类用例）

**8. 工作量：M**

**9. 推荐程度：高**

---

### 【排名 4】`prepareContext` 执行 ID 生命周期修正 —— 消灭孤儿目录与重复文件 promote

**1. 重构名称**
`ExecutionContext` 生命周期归位：ID 只生成一次，磁盘副作用只在提交阶段发生。

**2. 当前问题**

`ScriptExecutor.prepareContext` 的 L188–233 是本次评审发现的**最具体的真实缺陷**：

```java
// L189-193：用"临时" executionId promote 一次
if (req.getFileInputs() != null && !req.getFileInputs().isEmpty()) {
    Map<String, String> resolved = fileUploadService.promoteForExecution(
            nextExecutionId(), req.getFileInputs());     // ← 第 1 次 nextExecutionId()
    validated.putAll(resolved);
}

long executionId = nextExecutionId();                     // ← 第 2 次 nextExecutionId()
Path execDir = storagePathService.executionDirFor(executionId);
Files.createDirectories(execDir);
...
// L228-233：用"真"executionId 再 promote 一次
if (req.getFileInputs() != null && !req.getFileInputs().isEmpty()) {
    Map<String, String> resolved = fileUploadService.promoteForExecution(executionId, req.getFileInputs());
    ...
}
```

`FileUploadService.promoteForExecution`（L92–124）内部 `Files.createDirectories(execDir)`（L96）并 `Files.copy`（L120）。因此**每一次带文件参数的执行**都会：

1. 多消耗一个 executionId；
2. 在 `<executionsRoot>/<临时ID>/input/` 留下一份**完整文件副本 + 目录**；
3. 该临时目录**永远不会被清理**——`FileUploadService.cleanup(Long)`（L130–140）和 `cleanupPending(String)`（L146–158）在全项目**零调用点**（已用 grep 全仓库确认，唯一命中是定义本身）。

同时：

- `rerunFromSnapshot`（L843）又调了第 3 次 `nextExecutionId()`，与 L190/L195 共用一个递增计数器（`counter`，L92），三者耦合；
- `captureSnapshot`（L358–362）与 `finalizeExecution`（L487–491）各自写了一遍 `if (selectById == null) insert else update` 的 upsert 逻辑；
- `runPrecheckOrRecordFailure`（L310–320）又用 `newExecutionHistory` 造了第三份 history 行。

**3. 涉及哪些实际类**

`executor/ScriptExecutor.java`、`service/FileUploadService.java`、`mappers/ExecutionHistoryMapper`、`service/StoragePathService.java`、`entity/ExecutionHistory.java`

**4. 建议怎么改**

1. **先定 ID，再做副作用**：`long executionId = nextExecutionId()` 提到 `prepareContext` 最前面，L189–193 整段删除，只保留 L228–233 一次 promote。
2. 把 "创建 executionDir / promote 文件 / 写 history RUNNING 行" 收进一个 `ExecutionWorkspace`（记录 `executionId` 与各子目录），并明确其生命周期与 `close()`。
3. 在 `finalizeExecution` 的 `finally`（已有 wrapper 删除逻辑，L493–504）里补上 `fileUploadService.cleanup(executionId)` 与 pending 副本清理——让已存在但从未被调用的清理方法真正生效。
4. `selectById == null ? insert : update` 的 upsert 收敛成 `ExecutionHistoryWriter#upsert(history)` 一处。
5. `nextExecutionId()` 的生成策略从"时间戳 × 1000 + 自增"（`counter` L92）改为单调序列 + 冲突重试，或直接用 `execution_history.id` 的数据库序列，消除"进程重启后 ID 落入历史区间"的理论风险。

**5. 建议使用什么设计模式**

Unit of Work（一个执行 = 一个工作单元，含确定性的资源释放）+ 事务脚本（写入路径唯一）。

**6. 主要收益**

- 消除每次带文件执行的孤儿目录与重复文件副本（磁盘与清理审计双重债务）；
- executionId 不再跳号，`data/executions/` 与 `execution_history` 一一对应（`CleanupService.preview` 依赖目录名解析回 ID，`CleanupService.java:263`）；
- 三个 upsert / insert 路径合一，不会再出现"同一 executionId 两条历史行"。

**7. 风险：低**（改动局部、语义明确，且有 `FileParameterTest` / `ExecutionSnapshotTest` 覆盖）

**8. 工作量：S–M**

**9. 推荐程度：极高（投入产出比第一名，见 §5）**

---

### 【排名 5】PreCheck：消灭 3 份重复 JSON 解析 + 消除 `kerberos` 名字特例

**1. 重构名称**
PreCheck 策略族内部收敛 + `PrecheckResult` 类型化。

**2. 当前问题**

`PreCheck` **已经**是 Strategy + Registry（这是当前设计最正确的部分，见 §6），但内部有 4 处结构性问题：

1. **三份近乎逐字重复的 `items()`**：
   - `CommandExistsPrecheckStrategy.java:37–50`（15 行）
   - `FileExistsPrecheckStrategy.java:36–49`（14 行）
   - `DirectoryWritablePrecheckStrategy.java:37–50`（14 行）
   - `DirectoryWritablePrecheckStrategy.java:37–50`
   三者只差 `TYPE` 常量（`"commands"` / `"files"` / `"writableDirectories"`）与 `mapper.readValue` 的类型引用。
2. **`PrecheckService` 里硬编码的 `kerberos` 名字特例**（L113–115）：
   ```java
   String displayName = (type.equals("kerberos"))
           ? "Kerberos"
           : (type + ":" + (outcome.item() == null ? "" : outcome.item()));
   ```
   而 `KerberosPrecheckStrategy` 自己还维护了一份 `DISPLAY_NAME`（L23）与 `displayName()`（L59–61）—— **两份必须手动同步的显示名来源**，且策略侧的 `displayName()` 无人调用。
3. **`CheckResult` 内部类是死代码**：`PrecheckService.CheckResult`（L48–57）定义了完整的 `name/ok/message` 结构，但 `run()` 全程用 `private static Map<String,Object> toMap(...)`（L128–134）手工拼，`CheckResult` 零使用点。
4. **跨策略隐式契约 "self"**：`KerberosPrecheckStrategy.items()` 返回 `List.of("self")`（L39），这个约定只能靠注释传达，`PrecheckService` 与它没有任何类型层面的约束。
5. `PrecheckService.run()` 返回 `Map<String,Object>`，调用方 `ScriptExecutor` 只能写 `Boolean.TRUE.equals(precheck.get("ok"))`（L302）这种字符串键取值。

**3. 涉及哪些实际类**

`service/PrecheckService.java`、`service/precheck/PrecheckStrategy.java`、`service/precheck/PrecheckStrategyRegistry.java`、`service/precheck/{CommandExists,FileExists,DirectoryWritable,Kerberos}PrecheckStrategy.java`、`executor/ScriptExecutor.java`、`controller/PrecheckController.java`

**4. 建议怎么改**

1. `PrecheckStrategy` 接口补齐：
   ```java
   String configKey();                        // 替代裸 type()
   default String displayName(String item);   // 默认 type()+":"+item，Kerberos 覆写为 "Kerberos"
   default List<String> parseItems(JsonNode cfg) { ... }   // 模板方法，默认实现收敛 3 份重复
   ```
2. 三个 JSON 型策略改为 `extends AbstractJsonListPrecheckStrategy`，**只在子类声明 `configKey()`**，`items()` 的解析逻辑上提到基类（-40 行重复）。
3. 删除 `PrecheckService` 里的 `type.equals("kerberos")` 分支，改用 `strategy.displayName(item)`；**同时删掉 `KerberosPrecheckStrategy.displayName()` 死方法**，只保留一处定义。
4. `run()` 返回类型改成 `record PrecheckReport(boolean ok, boolean skipped, String message, List<CheckResult> results)`，删除死类 `CheckResult` 或让它成为真正的返回元素。
5. `"self"` 改为常量 `PrecheckStrategy.SELF` 或改用 `Optional<String>` 语义，去掉魔法字符串。

**5. 建议使用什么设计模式**

Template Method（`AbstractJsonListPrecheckStrategy`）+ Registry（保留）+ 类型化值对象。

**6. 主要收益**

- PreCheck 新增一种检查类型的成本从"抄 15 行 + 改 Service 分支"降到"声明一个 configKey + 写一个 check()"；
- 显示名规则单一来源，UI 文案不会再漂移；
- `run()` 结果类型化，`Boolean.TRUE.equals(map.get("ok"))` 消失；
- 死代码清零。

**7. 风险：中**（`PrecheckServiceTest` 有 8 个用例，需确认返回结构变化对 `/api/scripts/{id}/precheck` 前端契约的影响——建议 record 字段名与现有 Map 键名**保持一致**以做到零前端改动）

**8. 工作量：M**

**9. 推荐程度：高**

---

### 【排名 6】并发准入原子化 —— 用 `ExecutionGate` 取代 `check-then-act`

**1. 重构名称**
并发控制收口：单一 `ExecutionGate` 承担槽位、按脚本去重、取消状态。

**2. 当前问题**

当前并发控制是 **check-then-act 竞态**，且分散在三处：

1. `ScriptExecutor.prepareContext` L157–165 遍历 `runningRegistry.activeExecutions()` 判断"同脚本是否已在跑"；
2. `ScriptExecutor.startProcess` L375–380 再读一次 `runningRegistry.activeCount() >= props.getMaxConcurrent()`；
3. `ProcessRunner.run` L83 才真正 `registry.register(...)`。

在 1 与 3 之间存在任意长的窗口（参数校验、preset 查询、`Files.createDirectories`、`createKinitWrapper`、甚至 `SyntaxCheckService` 的 `bash -n`），**N 个并发请求可以同时通过检查**，随后各自 `register`，`maxConcurrent` 被静默击穿。`ScriptBoxProperties.java:43-44` 的注释写"由 ProcessRunner 的 Semaphore 强制控制"——**代码里根本没有 Semaphore**，注释与实现不符。

其它并发问题：

- `BatchService.runParallel`（L136–141）自建 `Executors.newFixedThreadPool` + 自己的上限 8（L133），与 `maxConcurrent` 是**两套互不知情的并发语义**；外层线程池满了不会让内层槽位等待，只会堆积。
- `RunningExecutionRegistry.unregister`（L66–69）先 `live.remove()` 再 `re.finished.set(true)` —— **顺序反了**。若 `cancel()`（L101–110）恰在 `remove` 与 `finished.set` 之间读到旧引用，会拿到一个已从 map 移除、但仍处于"未结束"状态的条目并对其 `destroyForcibly()`。`ProcessRunner` 与 `finalizeExecution` 各自调用 `unregister`（L140 与 ScriptExecutor L483），双路径使该窗口出现概率进一步上升。
- 槽位释放时机不一致：`ProcessRunner` 在 drain 完就注销（L140），而业务收尾（result 解析、artifact 扫描、落库）在 `ScriptExecutor.finalizeExecution` 里才做完，**二者之间进程已不可 cancel**。

**3. 涉及哪些实际类**

`service/RunningExecutionRegistry.java`（主改，可更名为 `ExecutionGate`）、`executor/ScriptExecutor.java`、`executor/ProcessRunner.java`、`service/BatchService.java`、`config/ScriptBoxProperties.java`、`controller/ExecutionController.java`（cancel/active/state 三个端点依赖它）

**4. 建议怎么改**

1. 提供 `tryAcquire(scriptId, allowConcurrent) -> Optional<ExecutionPermit>`：**在单个 `ConcurrentHashMap.compute` / `synchronized` 临界区内同时完成"槽位计数 + 同脚本去重 + 占位登记"**，把窗口压到零。`ScriptExecutor` L157–165 与 L375–380 两段检查删除。
2. `ExecutionPermit` 实现 `AutoCloseable`，与 `ExecutionContext` 一同 try-with-resources，槽位释放与执行生命周期**严格对齐**（进程结束 + 收尾完成才释放）。
3. 修 `unregister` 的指令顺序：`finished.set(true)` 必须在 `live.remove()` **之前**。
4. `cancel()` 的 `finished` / `cancelled` 两次 `get` 改为单次 CAS 状态机（`RUNNING → CANCELLING → FINISHED`）。
5. `BatchService` 改用注入的 Spring `TaskExecutor`，并把 `concurrency` 语义与 `maxConcurrent` 对齐（批次并发上限 = min(请求值, 全局剩余槽位)）；`MAX_ROWS`（L33）与硬编码 8（L133）进配置。
6. 把 `maxConcurrent` 用 `Semaphore` 真正实现（或明确删除该注释）。

**5. 建议使用什么设计模式**

Gatekeeper / Permit（`ExecutionPermit` = 准入令牌）+ State Machine（取消状态）+ 单一临界区。

**6. 主要收益**

- `maxConcurrent` 从"尽力而为"变成"硬上限"，宿主机保护真正生效；
- 取消状态不再有双删窗口，`cancel` 语义变成可推理的状态机；
- 批次与单脚本共享同一套并发语义，`ConcurrencyGateTest` 才有真正的被测对象；
- 槽位释放时机与业务收尾对齐，避免"进程还在收尾但槽位已放行"。

**7. 风险：中–高**（并发语义修改容易引入新的死锁或槽位泄漏；`ExecutionCancellationTest`（8 例）与 `ConcurrencyGateTest`（8 例）是主要回归面）

**8. 工作量：M–L**

**9. 推荐程度：高**

---

### 【排名 7】分层修正：Controller 不得触碰 Mapper / QueryWrapper

**1. 重构名称**
Controller–Service–Mapper 边界收口 + 依赖注入统一。

**2. 当前问题**

**3 个 Controller 直接注入 MyBatis-Plus Mapper 并在方法体内手写 `QueryWrapper`**（已 grep 全量确认）：

| 位置 | 代码 |
|---|---|
| `ScriptController.java:45-47, 238-249` | 注入 `ExecutionHistoryMapper` / `ScriptPresetMapper` / `ScenarioStepMapper`，在 `relatedCounts()` 里手写 3 个 `selectCount(new QueryWrapper<>()...)` |
| `TenantController.java:36, 90-96` | 注入 `ExecutionHistoryMapper`，`selectCount(...eq("tenant_id", id))` |
| `ScenarioController.java:28, 82-88` | 注入 `ExecutionHistoryMapper`，`selectCount(...eq("scenario_id", id))` |

更严重的越界：

- `ScriptController.java:133` 在 Controller 内 `new com.fasterxml.jackson.databind.ObjectMapper()`；
- `ScriptController.java:353` **又** `new ObjectMapper()`（同一文件两次裸 new，绕过 Spring 容器里已配置的 `JacksonConfig`）；
- `ScriptController.java:362` 调用 `scriptService.getMapper().updateById(s)` —— **Service 把 Mapper 当 getter 暴露出去**（`ScriptService.java:280` 注释还自认这是"避免 controller 重复注入 mapper"）；
- `ScriptPackageService.java:191` 同样使用 `scriptService.getMapper().updateById(saved)`；
- `TenantController.test()` **整个 kinit/klist 子进程编排写在 Controller 里**（L129–176）。

**依赖注入风格也不统一**：`ScriptController` / `TenantController` 用 `@RequiredArgsConstructor`，而 `ScenarioController` / `BatchController` / `GlobalVariableController` / `PresetController` / `HistoryController` / `ScriptVersionController` / `SystemController` 用 `@Autowired` **字段注入**。

**3. 涉及哪些实际类**

`controller/{Script,Tenant,Scenario,Batch,GlobalVariable,Preset,History,ScriptVersion,System}Controller.java`、`service/{Script,Tenant,Scenario}Service.java`、`service/ScriptPackageService.java`

**4. 建议怎么改**

1. `related-counts` 聚合下沉：`ScriptService.relatedCounts(id) -> ScriptImpact`、`TenantService.relatedCounts(id)`、`ScenarioService.relatedCounts(id)`；Controller 只做协议转换。
2. 删除 `ScriptService.getMapper()`（L280）与其两个调用点，改为 Service 上的意图方法（如 `ScriptService.savePrecheckConfig(id, json)`）。
3. `new ObjectMapper()` 两处改用构造器注入的容器实例（`JacksonConfig` 已提供）。
4. `TenantController.test()` 的 kinit 逻辑整体搬到 `TenantService.testConnectivity(id)`（并结合建议 2，走 `CommandExecutor`）。
5. 统一为 `@RequiredArgsConstructor` + `private final`，删掉全部 `@Autowired` 字段注入。

**5. 建议使用什么设计模式**

Layered Architecture 收口 + Facade（Service 作为唯一业务门面）+ 依赖倒置（构造器注入）。

**6. 主要收益**

- 业务规则可脱离 HTTP 层单测（当前 `related-counts` 必须起完整 Spring 上下文才能测）；
- 序列化行为统一（消灭与 `JacksonConfig` 不一致的裸 `ObjectMapper`）；
- 3 个 Controller 从 355/164/107 行显著变薄，协议变更不再牵动 SQL；
- 消除 `getMapper()` 这一"Service 泄漏持久层"的坏先例。

**7. 风险：低**（纯搬迁，无行为变更；`UxEndpointsTest` / `ApiSmokeTest` 覆盖端点）

**8. 工作量：M**

**9. 推荐程度：高**

---

### 【排名 8】DTO / 边界收敛：`Map<String,Object>` 从 90 处降到个位数

**1. 重构名称**
响应与内部契约类型化（record 化），消灭字符串键约定。

**2. 当前问题**

全项目 `Map<String,Object>` 出现 **90 处**（已 grep 全量统计），其中承担**真实契约**的关键几处：

| 位置 | 问题 |
|---|---|
| `ScriptExecutor.preview()` L726–781 | 返回 15 键 Map（`scriptId/scriptName/scriptDisplayName/scriptPath/tenantId/tenantName/principal/timeoutSeconds/enabled/params/command/kinitWrapped/globalVariables/keytabSet/keytabPath`），前端靠字符串键消费 |
| `PrecheckService.run()` L66–126 | 返回 `ok/skipped/message/results`，调用方 `Boolean.TRUE.equals(get("ok"))` |
| `BatchService.summarize()` L205–221 | 每行 9 个键手工 `put` |
| `ArtifactService.listView()` L291–306 | 6 个键手工 `put` |
| `FileUploadService.savePending()` L46–78 | `token/originalName/absolutePath/size` 四键，与前端隐式耦合 |
| `GlobalVariableService.listSummary()` L51–69 | 8 个键，且与 `GlobalVariableController.get()` L45–53 的 `Map.of(...)` **两份形状近似但字段名不同**（`variableValueSet` vs `hasValue`）——同一个实体两套响应契约 |
| `ExecutionController.state/cancel/active` L104–158 | 6+ 处就地 `new LinkedHashMap<>()` |
| `AdminController.cleanupPreview/cleanupExecute` L82–119 | 直接以 `Map<String,Object>` 作为 `@RequestBody`，再手写 `readInt()` L191–198 做类型兜底 |
| `CleanupService.execute()` L318–321 | `String.format("{\"historyDays\":%d,...}")` **手工拼 JSON 字符串**存 `retention_json` |
| `CheckResult` / `RunEntry.toMap()` / `SyntaxResult.toMap()` | 三套自建 Map 序列化 |

这份清单里已经没有"数据形状未定"的正当理由——**它只是历史遗留**，代价是：字段名拼错编译期不报错、前端契约无单一来源、`readInt` 这类防御代码必须到处写、`@RequestBody Map` 无法用 `@Valid`。

**3. 涉及哪些实际类**

`dto/`（新增）、`executor/ScriptExecutor.java`、`service/{Precheck,Batch,Artifact,FileUpload,GlobalVariable,Cleanup}Service.java`、`controller/{Execution,Admin,GlobalVariable,Script,System}Controller.java`、`model/VisibleWhen.java`

**4. 建议怎么改**

新增以下 record（字段名**与现有 Map 键名逐一对应**，从而前端零改动）：

`ExecutionPreview`、`PrecheckReport` + `PrecheckCheckResult`、`BatchRowView`、`ArtifactView`、`UploadTicket`、`GlobalVariableView`（顺便统一 `variableValueSet` / `hasValue` 两份契约）、`ExecutionStateView`、`SystemInfo`、`CleanupPreviewRequest` / `CleanupExecuteRequest`、`RetentionSetting`（替代手拼 JSON）。

另外把两处同名异实的逻辑合并：

- `ScriptExecutor.validateAndCoerce`（L594–648，执行期丢弃不可见参数）与 `ScriptService.filterVisible`（L341–350，UI 期过滤）**是同一规则的两次实现**，合并进单一 `VisibilityContext` / `ParamResolver`；
- `HistoryService.deriveStatus`（L102–110）与 `ExecutionStatus.of()`（`ExecutionStatus.java:46–53`）**是同一状态归一化的两次实现**，前者还额外揉进了 `Boolean.TRUE.equals(h.getTimeout())` 的旧字段判断，合并为一处。

**5. 建议使用什么设计模式**

DTO / View Model + Value Object（record）+ Anti-Corruption Layer（前端契约与持久层实体解耦）。

**6. 主要收益**

- 字段名由编译器保证，前端契约单一来源；
- `readInt()` 类防御代码与 `Boolean.TRUE.equals(map.get("ok"))` 全部消失；
- `@RequestBody` 可用 Bean Validation（项目已依赖 `spring-boot-starter-validation`，当前几乎没用）；
- 执行期与 UI 期的 `visibleWhen` 规则合一，消灭"两处可能不一致"的隐患；
- 状态归一化单一来源，历史筛选与 UI 徽标不会再漂移。

**7. 风险：中**（响应结构变化对前端有影响；必须逐字段核对键名，任何改名都要同步前端）

**8. 工作量：M–L**（可按 Controller 分批）

**9. 推荐程度：中高**

---

### 【排名 9】异常体系落地 —— 31 个错误码只用上 2 个

**1. 重构名称**
`BusinessException` + `BusinessErrorCode` 全量落地，取代裸 JDK 异常。

**2. 当前问题**

`exception/BusinessErrorCode.java` 定义了 **31 个错误码**（`SCRIPT_NOT_FOUND` / `TENANT_DISABLED` / `EXECUTION_SLOT_LIMIT_REACHED` / `PATH_ESCAPE` / `SYNTAX_CHECK_FAILED` …），`BusinessException` 也齐备，但**全项目只有 2 个抛出点**——都在 `StoragePathService`（L136、L142，用 grep 全量确认）：

```
BusinessErrorCode.  → 命中 2 处，均在 service/StoragePathService.java
```

所有业务失败都是裸 `IllegalArgumentException` / `IllegalStateException`，`GlobalExceptionHandler`（L44–57）把它们一律映射为 `ApiResponse.error(msg)` → **前端只需要 `code` 分流，而它永远是 1**。

具体症状：

- `ScriptExecutor` 抛 `IllegalArgumentException("脚本不存在")`(L105) 与 `IllegalStateException("script not found")`(L728) 表达**同一件事**——一个是类型不一致，一个是语言不一致；
- `PrecheckService` 抛**中文** `"策略异常: "`(L109)，`ScriptExecutor` 抛**英文** `"script exceeds size limit"`(`ScriptService` L362)，`GlobalExceptionHandler` 自己也在中文（`"参数非法"`）与英文（`"io error: "`）之间摇摆；
- `AdminController` 另建**局部 `@ExceptionHandler`**（L48–64），把异常前缀化成字符串 `"PREVIEW_EXPIRED: "` / `"BAD_REQUEST: "` / `"EXECUTE_FAILED: "` 让前端 `startsWith` 分流——**这是把错误码降级成了字符串前缀**；
- `ScriptController` L50–58 / L176–182 等方法内 `try/catch` 再返回 `ApiResponse.error(e.getMessage())`，与 `GlobalExceptionHandler` 的职责重叠；
- `AnalysisResponse` 族（`ApiResponse.error`）永远返回 `code=1`，`BusinessErrorCode` 从未出现在任何响应里。

**3. 涉及哪些实际类**

`exception/{BusinessException,BusinessErrorCode,GlobalExceptionHandler}.java`、`executor/ScriptExecutor.java`、`service/{Script,Tenant,Preset,Precheck,FileUpload,Cleanup,SyntaxCheck}Service.java`、`controller/{Admin,Execution,Script,Preset,ScriptVersion}Controller.java`、`dto/ApiResponse.java`

**4. 建议怎么改**

1. `ApiResponse` 升级为 `ApiResponse<T>(String errorCode, String message, T data)`，成功时 `errorCode=null`；`ok()` / `error(code, msg)` 两个工厂。
2. 业务层统一抛 `BusinessException(BusinessErrorCode, args)`，消息通过错误码集中渲染（可带 i18n），**不再在 30 个调用点各写一句中文**。
3. `GlobalExceptionHandler` 收敛为 `BusinessException → 具体 errorCode`；`IllegalArgumentException` / `IllegalStateException` 降级为 `INTERNAL_ERROR` 并记录**迁移清单**（保留过渡期兼容）。
4. 删除 `AdminController` 的 3 个局部 `@ExceptionHandler`，前端改为读 `errorCode`；`PREVIEW_EXPIRED` 已在枚举里（L47），直接复用。
5. 删除 Controller 内的 `try/catch`（`ExecutionController.run/preview/rerun`、`PresetController.create/update`、`ScriptVersionController.rollback`、`ScenarioController.run`、`BatchController.execute`），全部交给 advice。

**5. 建议使用什么设计模式**

Domain Exception + Centralized Translator（`@RestControllerAdvice`）+ Error Code Enum。

**6. 主要收益**

- 前端可用稳定 `errorCode` 分流，不再解析消息文本或字符串前缀；
- 错误消息语言与措辞单一来源；
- 31 个枚举从"文档"变成"活的契约"；
- Controller 里的样板 `try/catch` 全部消失。

**7. 风险：中**（前端目前可能依赖 `code` / 消息文案；需与前端同步一次契约，建议保留 `code` 字段语义标记为 deprecated 而非删除）

**8. 工作量：M**

**9. 推荐程度：中高**

---

### 【排名 10】配置与常量收口 —— 三套配置源 + 散落 magic number

**1. 重构名称**
配置单一可信源（`scriptbox.*` ⇄ `system_setting`）+ 常量外置。

**2. 当前问题**

**同一参数被定义 2–3 次，且无单一可信源**：

| 参数 | 定义位置 |
|---|---|
| 默认超时 600 | `db/schema.sql:20`（`DEFAULT 600`）+ `ScriptBoxProperties` 无此字段 + `ScriptExecutor.java:206` + `ScriptController.java:92,124,167` + `ScriptPackageService.java:150` + `ExecutionContext.Builder` L70 —— **6 处** |
| 执行器可执行名 `bash` | `ScriptExecutor.java:521,571,753,785` + `SyntaxCheckService.java:100` + `ScriptExecutor.java:562` 的 `#!/usr/bin/env bash` —— **6 处**，且无配置项 |
| 保留天数 | `application.yml:56-61`（YAML）+ `ScriptBoxProperties:53-57`（Java 默认值）+ `system_setting` 表（`CleanupService.K_*` L56–59 运行时覆盖）—— **3 套来源** |
| 产物上限 | `max-artifact-bytes` / `max-artifact-files` 在 YAML L49-50 与 `ScriptBoxProperties:48-49` 各有一份；而 `maxArtifactTotalBytes`（`ScriptBoxProperties:50`、YAML L51）**在全项目零读取点**——**死配置** |
| 并发上限 | YAML L（**缺失**，`max-concurrent` 根本不在 `application.yml` 里）+ `ScriptBoxProperties:45` 默认 5 + `BatchService` 硬编码 8（L133） |
| 批次数上限 200 | `BatchService.MAX_ROWS` L33（且 L82-83 与 L130-131 **两处重复校验**） |
| 预览 TTL 10 分钟 | `PreviewStore.PREVIEW_TTL_MS` L27（不可配） |
| 日志读取上限 | `props.getMaxLogBytes()`，默认 `1048576`（`ScriptBoxProperties:38`），但它**不是**落盘上限（见建议 3） |
| 其它散落字面量 | `86_400_000L`（`CleanupService.java:138,221`）、`1800`（L353 截断长度）、`96`（`FileUploadService.java:61` 文件名截断）、`MAX_ENTRY_BYTES` 1MB（`ScriptPackageService.java:47`）、`MAX_BYTES` 1MB（`ResultParserService.java:29`）、`200`（`CleanupService.recentHistory` L362）、`500` / `50` / `20` / `10`（`HistoryService` L31,43,81、`HistoryController.java:73`） |

另外 `ScriptBoxProperties.java:43-44` 的注释声称并发由 "ProcessRunner 的 Semaphore" 控制，而实现是 `RunningExecutionRegistry` 的计数检查（见建议 6）——**注释与实现不一致**。

**3. 涉及哪些实际类**

`config/ScriptBoxProperties.java`、`src/main/resources/application.yml`、`service/SystemSettingService.java`、`service/CleanupService.java`、`executor/ScriptExecutor.java`、`service/SyntaxCheckService.java`、`service/BatchService.java`、`service/HistoryService.java`、`service/FileUploadService.java`、`service/ScriptPackageService.java`、`service/ResultParserService.java`、`service/PreviewStore.java`、`controller/ScriptController.java`

**4. 建议怎么改**

1. 定义 `ScriptBoxSettings` 接口作为唯一读取入口，内部实现"`system_setting` 覆盖 > `application.yml` > 代码默认"的优先级；`ScriptBoxProperties` 只作绑定载体，`CleanupService.getInt(K_*, props.get*)` 的 4 处手写优先级（L77–92）收敛进去。
2. `application.yml` 补齐 `max-concurrent`（当前缺失）并加 `shell-executable` / `kinit-executable`。
3. 默认超时 600 收进 `ScriptBoxProperties.defaultTimeoutSeconds` + `db/schema.sql` 同步；`ScriptExecutor:206`、`ScriptController:92,124,167`、`ScriptPackageService:150` 全部改读。
4. `BatchService` 的并发上限 8、`MAX_ROWS` 200 进配置；**删掉 L82-83 与 L130-131 的重复行数校验**（抽成 `validateRows`）。
5. 删除死配置 `maxArtifactTotalBytes`，或按建议 3 的 `OutputBudget` 真正接线。
6. 全部 `86_400_000L` → `Duration.ofDays(n).toMillis()`；`1800` / `96` / `200` / `500` / `50` / `20` 用具名常量。
7. 用 `@ConfigurationProperties` + `@Validated` + `@Min/@Max` 做范围校验，替代 `setMaxConcurrent` / `setRetention*` 里两个参数值的 `Math.max` 手工校验（`ScriptBoxProperties:63-90`）。

**5. 建议使用什么设计模式**

Configuration Object + Chain of Responsibility（配置源优先级链）+ Fail-Fast Validation（`@Validated`）。

**6. 主要收益**

- 改一个默认超时/命令名/保留天数只需动一处；
- `maxConcurrent` 这类关键参数有唯一语义，配合建议 6 形成闭环；
- 死配置清零，运维不再被"改了不生效"误导；
- YAML 与 Java 默认值不会再漂移。

**7. 风险：中**（配置读取顺序变化可能影响 `CleanupService` 的既有行为；需回归清理相关用例）

**8. 工作量：M**

**9. 推荐程度：中高**（长期收益大，但紧迫性低于排名 1–6，故列 Phase 2）

---

## 3. 汇总表（按价值排序 1–10）

| 排名 | 重构项 | 收益 | 风险 | 工作量 | 推荐程度 |
|:---:|---|:---:|:---:|:---:|:---:|
| 1 | `ScriptExecutor` 编排拆解（Pipeline + Prepaer + CommandBuilder） | 极高 | 高 | XL | ★★★★★ |
| 2 | 统一子进程执行契约（`CommandExecutor` 收口 3 处 ProcessBuilder + `shell-executable` 可配） | 高 | 中 | M–L | ★★★★★ |
| 3 | 输出 drain 忠实性与进程残留兜底（流式重定向 + `OutputBudget` + 终止校验） | 高 | 中 | M | ★★★★☆ |
| 4 | `prepareContext` 执行 ID 生命周期修正（消灭孤儿目录 / 重复 promote） | 高 | 低 | S–M | ★★★★★ |
| 5 | PreCheck 策略族收敛（消灭 3 份重复解析 + `kerberos` 特例 + 类型化结果） | 中高 | 中 | M | ★★★★☆ |
| 6 | 并发准入原子化（`ExecutionGate` 取代 check-then-act） | 高 | 中–高 | M–L | ★★★★☆ |
| 7 | 分层修正（Controller 不得触碰 Mapper / `getMapper()` / 裸 `ObjectMapper`） | 中高 | 低 | M | ★★★★☆ |
| 8 | DTO 边界收敛（`Map<String,Object>` 90 → 个位数；`visibleWhen` / 状态归一化合一） | 中高 | 中 | M–L | ★★★☆☆ |
| 9 | 异常体系落地（31 个 `BusinessErrorCode` 只用 2 个 → 全量） | 中高 | 中 | M | ★★★☆☆ |
| 10 | 配置与常量收口（三套配置源合一 + magic number 外置） | 中 | 中 | M | ★★★☆☆ |

---

## 4. 分阶段建议

### Phase 1：优先做（**正确性 / 资源安全 / 可运行性**，互不冲突，可并行）

| 项 | 排名 | 理由 |
|---|:---:|---|
| 执行 ID 生命周期修正 | 4 | 风险低、工作量 S–M，直接消灭真实磁盘/审计债务，且**是排名 1 的前置步骤** |
| 统一子进程执行契约 | 2 | 修复两处可挂死 Tomcat 线程的 `waitFor()`；`shell-executable` 可配后本机 `mvn test` 才有意义 |
| 输出 drain 与进程残留兜底 | 3 | 修 `readUpTo` 短读缺陷、日志不忠实、磁盘打满风险 |
| 分层修正 | 7 | 风险低、纯搬迁，为排名 1 的重构腾出干净的 Controller |

> Phase 1 建议顺序：**4 → 2 → 7 → 3**。先拿到"能跑、不泄漏、边界干净"的基本盘。

### Phase 2：后续做（**结构级重构**，依赖 Phase 1 的干净基线）

| 项 | 排名 | 理由 |
|---|:---:|---|
| `ScriptExecutor` 编排拆解 | 1 | 收益最大但风险最高；必须在建议 4 落地、且有可用测试基线之后动 |
| PreCheck 策略族收敛 | 5 | 独立子系统，风险可控，可在排名 1 推进期间并行 |
| 并发准入原子化 | 6 | 需要先有可信的 `maxConcurrent` 配置语义（依赖排名 10 的配置收口） |
| DTO / 边界收敛 | 8 | 与前几项有文件交集，放在排名 1 之后减少冲突 |

### Phase 3：可以以后做（**长期可维护性 / 契约质量**）

| 项 | 排名 | 理由 |
|---|:---:|---|
| 异常体系落地 | 9 | 需要与前端同步一次契约，价值高但不阻塞 |
| 配置与常量收口 | 10 | 纯收敛性工作，无功能风险，适合作为技术债清理项持续做 |

### 另外两件"顺手就做"的小事（不属于 10 条，但建议同批处理）

1. `pom.xml` 删除重复的 Lombok 声明（保留 L48–53 的 `<optional>true</optional>` 一处即可）。
2. `StoragePathServiceTest.java:50` 的 `endsWith("scripts/42/script.sh")` 改为 `endsWith(Paths.get("scripts","42","script.sh"))`，消除平台耦合（这是当前 1 个 Failure 的唯一原因）。

---

## 5. 专项结论

### 5.1 当前最值得重构的 3 个地方

1. **`executor/ScriptExecutor.java`（排名 1）**
   885 行、12 种职责、`prepareContext` 单方法 112 行、`preview()` 整段复制执行期逻辑。它是全系统的唯一执行收口，也是所有结构问题的交汇点。拆完之后建议 2/3/4/6 的收益才真正可维护。

2. **子进程执行的三份平行实现（排名 2）**
   `ProcessRunner` / `SyntaxCheckService.tryBashN` / `TenantController.test` 各自 `ProcessBuilder`，只有第一份有超时与回收。**这是当前最直接的生产风险**：两个无超时 `waitFor()` 挂在 Tomcat 工作线程上，而其中一个 (`SyntaxCheckService`) 还被 `ScriptService` 设成了所有写路径的强制关卡，并被 `/api/scripts/syntax-check` 以"随打随探"的频率调用。

3. **`ScriptExecutor.prepareContext` 的 executionId 生命周期（排名 4）**
   L189–193 与 L228–233 的重复 promote 不是风格问题而是**确定的资源泄漏**：每次带文件参数的执行都会留下一个永不被清理的 `<executionsRoot>/<临时ID>/input/` 目录及文件副本；配套的两个清理方法（`FileUploadService.cleanup` / `cleanupPending`）在全项目**零调用点**。同时 `execution_history` 的 ID 与 `data/executions/` 目录名会出现空洞，而 `CleanupService.makeExecutionCandidate`（L256–280）正依赖"目录名可解析回 executionId"这一约定。

### 5.2 风险最大的一项

**排名 1 —— `ScriptExecutor` 编排拆解（风险：高 / 工作量：XL）。**

三重风险叠加：

1. **回归面最大**：它是所有执行路径（单脚本 / 批量 / 场景 / 快照重放 / dry-run）的唯一收口，直接牵动 `ScriptExecutorTest`、`BatchServiceTest`、`ScenarioServiceTest`、`ConcurrencyGateTest`、`ExecutionCancellationTest`、`ExecutionSnapshotTest`、`RiskLevelExecutionTest`、`DryRunTest`、`FileParameterTest` 共 **9 个测试类 / 51 个用例**——而其中 51 个用例在本机**当前全部为 Error 状态**（§1.3A），也就是说**没有可用的安全网**。
2. **语义风险**：`preview` 与真实执行目前行为不同（dry-run 不做 DANGEROUS/enabled 校验），合并时若"取并集"会改变 dry-run 的对外行为，可能让前端在预览阶段提前报错。
3. **顺序风险**：`captureSnapshot`（写 RUNNING 行）与 `finalizeExecution`(写最终结果) 之间存在跨方法的状态耦合，拆分边界画错会引入"history 行丢失"或"重复插入"。

**缓解措施**：先做 Phase 1 的四项（尤其是 4 和 2），把 `mvn test` 恢复到绿色作为安全网，再按 `ExecutionPreparer` → `CommandBuilder` → `ExecutionFinalizer` 三步分批提交，每步单独回归。

### 5.3 投入产出比最高的一项

**排名 4 —— `prepareContext` 执行 ID 生命周期修正（风险：低 / 工作量：S–M）。**

改动量极小：把 `long executionId = nextExecutionId();` 上移到方法开头、删除 L189–193 那一段、在 `finalizeExecution` 的 `finally` 里补两行清理调用、抽一个 `upsert` 辅助方法。换来的收益是结构级的：

- 消灭每次带文件执行的孤儿目录 + 重复文件副本（真实磁盘债务）；
- 让 `FileUploadService.cleanup` / `cleanupPending` 这两个**已经写好但从未被调用**的方法真正生效；
- executionId 不再跳号，`data/executions/` 目录名与 `execution_history.id` 恢复一一对应（`CleanupService` 的目录→ID 解析依赖此约定）；
- 三处 `insert/update` 判定（`captureSnapshot` L358-362、`finalizeExecution` L487-491、`runPrecheckOrRecordFailure` L310-320）合一，消除"同一 ID 两条历史行"的可能。

### 5.4 3 个目前设计合理、不建议乱改的地方

1. **PreCheck 的 Strategy + Registry 架构（`service/precheck/`）**
   `PrecheckStrategy` + `PrecheckStrategyRegistry` 的自动收集（构造器注入 `List<PrecheckStrategy>` 分组 `Map<String,List<...>>`）+ `PrecheckService` 只做遍历汇总，这个**外层架构是对的**，新增检查类型确实不需要改 `PrecheckService.run()`。排名 5 只做**内部**收敛（消重复代码、去名字特例），**不要**动这个模式，更不要引入更重的规则引擎——那会变成过度设计（当前只有 4 种检查）。

2. **`StoragePathService` 作为唯一路径安全门（`assertInside` / `isInsideReal` / `safeList`）**
   受控根集中、`toAbsolutePath().normalize()` 前缀比较（`assertInside` L134-145）用于语法层校验、`toRealPath(NOFOLLOW_LINKS)`（`isInsideReal` L156-166）用于删除场景，这套双重策略是正确的；`ArtifactService.resolveSafe`（L173-223，拒绝 `..` / 绝对路径 / Windows 盘符）与 `FileUploadService` 的 `uploadsRoot` 前缀校验（L109-112）都在正确的位置调用了它。清理侧还叠加了"软链接一律跳过 + 运行中二次确认"（`CleanupExecutor.deleteExecutionDir` L168-191）——**这是全项目安全设计最扎实的一块，重构时只应扩展（新增路径种类也必须走它），不应改写算法。**

3. **`ProcessRunner` 的 stdout/stderr 并行 drain 设计**
   必须并行消费两个流、daemon 线程、`joinQuietly` 带 2 秒上限（`DRAIN_JOIN_MS` L45）、`redirectErrorStream(false)` 保持顺序独立——这是避免 `Process` 管道缓冲死锁的正确做法，注释（L24-27）也把原因写清楚了。排名 2/3 可以改为内核重定向或流式写入来提升忠实性，但**"两个流必须各自被持续消费"这一结构性约束不能动**。

4. **（bonus）清理流程的 Preview → Confirm → Execute 三段式**
   `CleanupService.preview` 纯读、`PreviewStore` 带 TTL（`PREVIEW_TTL_MS` L27）与快照 ID 绑定、`CONFIRM_TOKEN = "CLEAN"` 字面量确认（L62、L307）、执行后写 `cleanup_history` 审计行——对"删除类操作必须有可核对的前置快照"这一原则执行得很彻底。同样不建议改动其安全模型（可把 `CONFIRM_TOKEN` 与前端字符串前缀错误处理收进建议 9/10 的常量与错误码体系，但流程本身保留）。

---

## 6. 附：本次未做与未覆盖

- **未修改任何 Java 文件**，未执行 `git add` / `git commit`；
- **未读取前端源码**（`frontend/`）：涉及前端契约的建议（排名 8 的 DTO 改名、排名 9 的 `errorCode`）已明确要求"字段名/错误码与现有约定对齐"，但落地前仍需与前端核对一次；
- 未做静态分析工具（SpotBugs / PMD / Sonar）扫描，结论全部来自逐文件通读与真实执行结果；
- `mvn test` 的 131 个 Error 是**环境性根因**（WSL bash 路径）而非代码缺陷，但排名 2 的配置外置能使其在本机可运行；剩余的 1 个 Failure 是真实的平台耦合缺陷，修复方式见 §4 末尾。
