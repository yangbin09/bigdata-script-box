# 交接文档：Java 大型重构执行进度（HANDOFF）

> 本文件为**交接用途**，记录 `JAVA_MAJOR_REFACTOR_REVIEW.md` 那 10 条重构的执行现状，
> 供新会话直接接手。**未提交 Git**，全部改动都在工作区。

- 项目：`D:\code\java\shell\demo\bigdata-script-box`
- 分支：`main`（改动未暂存）
- 变更规模：**35 个文件改动 + 7 个新文件 / 1 个删除**
- 测试状态：**`Tests run: 158, Failures: 0, Errors: 0, Skipped: 0` → `BUILD SUCCESS`**（已连续多次复跑确认稳定）
  - 起点：`156 run, 1 Failure, 131 Errors`

---

## 1. 怎么编译 / 跑测试（重要，环境有坑）

`mvn` 不在 `PATH`，用 wrapper 里那份：

```powershell
$MVN = "C:\Users\16922\.m2\wrapper\dists\apache-maven-3.9.9-bin\4nf9hui3q3djbarqar9g711ggc\apache-maven-3.9.9\bin\mvn.cmd"
cd D:\code\java\shell\demo\bigdata-script-box

# 编译
& $MVN -B -Pgit-bash -DskipFrontend -o test-compile

# 全量测试（必须先建好下面的目录联接）
& $MVN -B -Pgit-bash -DskipFrontend -o test
```

**为什么必须带 `-Pgit-bash`**：本机 `C:\Windows\system32\bash.exe` 是 **WSL 转发器**，
它会把 `C:\Users\...` 吞成 `C:Users...`（反斜杠全丢），任何带路径的 bash 调用都失败。
这就是重构前 `mvn test` 有 **131 个 Error** 的根因。Git Bash 能正确处理 Windows 路径。

**目录联接（已在本机创建，换机器需要重建）**：Git Bash 默认路径 `C:\Program Files\Git\bin`
**含空格**，而 Maven 的 `<argLine>` 无法安全传带空格的 `-D` 值（fork 出的 JVM 会把它
拆成两个参数，导致 surefire 直接起不来）。所以建了个无空格联接：

```powershell
New-Item -ItemType Junction -Path 'C:\gitbash' -Target 'C:\Program Files\Git\bin' -Force
```

`pom.xml` 的 `git-bash` profile 默认指向 `C:/gitbash/bash.exe`，装到别处可覆盖
`-Dgit.bash.path=...`。**生产（Linux）不受影响**，默认仍是 `bash`。

---

## 2. 已完成的重构（7 条，全部编译通过 + 测试通过）

### ✅ #4 `prepareContext` executionId 生命周期修正
`executor/ScriptExecutor.java`
- executionId 从"在 prepareContext 里生成两次"改为**在 `executeWithScript` 入口生成一次**，
  作为参数传入 `prepareContext(req, script, executionId, scriptBodyOverride)`。
- 删掉了那段用**临时 executionId** 先 `promoteForExecution` 一次、之后又用真 ID 再 promote
  一次的重复代码 —— 它每次带文件参数执行都会在磁盘留下**永不清理的孤儿目录 + 重复文件副本**。
- 抽出 `resolveParams(req, script)`（preset 合并 + 校验），`preview()` 现在复用同一份逻辑
  （此前是整段复制，已经和真实执行语义漂移）。
- 抽出 `upsertHistory(history, isNew)`，合并原先三处各自写的 insert/update 判断。
- `finally` 里调用 `cleanupPendingUploads(...)` → 真正用上了此前**零调用点**的
  `FileUploadService.cleanupPending`。**注意**：刻意**不删** `<execId>/input/`，
  它属于 executionsRoot、由 CleanupService 按保留天数回收（删了会让运维无法复核输入文件）。

### ✅ #2 统一子进程执行契约
**新增**：`executor/CommandExecutor.java`、`CommandSpec.java`、`CommandResult.java`
- `CommandExecutor.exec(CommandSpec) -> CommandResult` 成为全项目唯一子进程契约。
- `ProcessRunner implements CommandExecutor`，是唯一生产实现。
- `service/SyntaxCheckService.java`：删掉自己的 `new ProcessBuilder("bash","-n",...)` +
  **无超时 `waitFor()`**（它被 `ScriptService.create/update/saveScriptBody` 设为强制关卡，
  还被 `/api/scripts/syntax-check` 以"随打随探"频率调用 → 可挂死 Tomcat 线程）。
  现在走 `CommandExecutor`，并保留原有的启发式 fallback。
- `service/TenantService.java`：**新增 `testConnectivity(Long)` + `TenantConnectivity` record**，
  把原先写在 `TenantController.test()` 里的 kinit/klist 进程编排下沉（同样是无超时 `waitFor()`
  + `readAllBytes()` 死锁组合）。`TenantController` 现在只做协议转换。
- `config/ScriptBoxProperties.java` 新增：`shellExecutable`（默认 `bash`）、
  `kinitExecutable`、`klistExecutable`、`defaultTimeoutSeconds`、`maxOutputBytes`、
  `commandTimeoutSeconds`，并带边界校验 setter。
  `ScriptExecutor` 里 6 处硬编码的 `"bash"` / `#!/usr/bin/env bash` 全部改读配置。

### ✅ #3 输出 drain 忠实性 / 进程残留 / 短读
`executor/ProcessRunner.java`（**已完全重写**）
- **输出改为纯内核重定向**（`pb.redirectOutput(File)`），彻底不再读管道。
  原因见 §4 的坑：管道 + 强杀 = 读取线程永远等不到 EOF（Windows 上孙进程还握着写端），
  而从另一个线程 `close()` 一个正被 `read()` 的流在 Windows 上**并不生效**。
- 输出上限改为**进程结束后截断文件**（`truncateToBudget`），不再靠应用侧限量写入。
- `destroyTree` 之后**验证**是否真的死了，残留则 `log.error` 并置位 `lingering`。
- `ScriptExecutor.readUpTo` 修掉**短读缺陷**（旧实现只读一次，短读时返回未初始化的零字节）。

### ✅ #7 分层修正（由 subagent 完成，已验证）
- 3 个 Controller 不再注入 Mapper / 手写 `QueryWrapper`：
  `ScriptController.relatedCounts` / `TenantController` / `ScenarioController`
  → 下沉为 `ScriptService.relatedCounts` / `TenantService.relatedCounts` /
  `ScenarioService.relatedCounts`（**返回键名保持不变**：`historyCount`/`presetCount`/`scenarioCount`）。
- **删除了 `ScriptService.getMapper()`** 这一"Service 泄漏持久层"的坏先例；
  新增 `ScriptService.savePrecheckConfig(Long, String)` 意图方法。
- `ScriptController` 里 2 处裸 `new ObjectMapper()` 改为注入容器实例（原来绕过了 `JacksonConfig`）。
- 7 个用 `@Autowired` 字段注入的 Controller 统一成 `@RequiredArgsConstructor` + `private final`。

### ✅ #6 并发准入原子化
**新增**：`service/ExecutionGate.java`；**删除**：`service/RunningExecutionRegistry.java`
- 原本是 check-then-act 竞态：`prepareContext` 遍历 registry 查同脚本 →
  `startProcess` 再读一次 `activeCount()` → `ProcessRunner` 最后才 register，
  中间窗口长到足以让 N 个并发请求同时通过检查、静默击穿 `maxConcurrent`。
  而 `ScriptBoxProperties` 注释还写着"由 ProcessRunner 的 Semaphore 强制控制"——**根本没有 Semaphore**。
- `ExecutionGate.acquire(...)` 在**单个 `synchronized` 临界区**内同时完成
  "同脚本去重 + 槽位余量检查 + 占位登记"，窗口为零。
- `Permit implements AutoCloseable`：槽位从 `pb.start()` 之前一直持有到
  整段执行（含 result.json 解析、artifact 扫描、落库）结束才释放。
- `ProcessRunner` 只负责 `gate.bindProcess(executionId, process)`（让 cancel 能 kill 到它）
  和 `gate.releaseProcess(...)`；槽位释放归 `ScriptExecutor` 的 try-with-resources。
- `ExecutionController` / `CleanupService` / `CleanupExecutor` 已全部改到 `ExecutionGate`。

**顺带修掉的真实缺陷**（重构中发现的）：
`ProcessRunnerTest.nonZeroExitReported` 暴露 —— `waitFor(timeout)` 返回 `false` **不等于超时**，
也可能是"进程已退出但 onExit 通知未送达"。旧实现把 `false` 直接当超时，
会把 `bash -c "exit 7"` 的退出码 **7 改写成哨兵值 -1** 并误报 `timeout=true`。
现在改为：`waitFor` 返回 false 后再问一次 `process.isAlive()`，只有确实还活着才算超时。

---

## 3. 顺带完成的清理

- `pom.xml`：删掉**重复声明**的 Lombok（第 48–53 行与 60–64 行各一份）；
  新增 `scriptbox.shell-executable` 属性 + `git-bash` profile + surefire 传参。
- `StoragePathServiceTest.pathGenerationFollowsConvention`：把硬编码 `/` 的
  `endsWith("scripts/42/script.sh")` 改为 `endsWith(Paths.get("scripts","42","script.sh"))`，
  这是原报告里那 **1 个 Failure** 的原因（平台耦合，非产品缺陷）。
- `CommandExistsPrecheckStrategy`：当被检查命令是配置 shell 的可执行名时（**容忍 `.exe` 后缀**），
  按 `scriptbox.shell-executable` 解析。否则出现"precheck 说没装 bash，而脚本其实能跑"的自相矛盾。
- `DryRunTest` 不再写死 `assertEquals("bash", cmd.get(0))`，改断言 `props.getShellExecutable()`。

---

## 4. 已解决：编码与进程句柄问题（保留记录，供理解设计取舍）

这两个问题在收尾阶段已全部修复，**不再是遗留项**。记录在此是因为它们决定了
`ProcessRunner` 现在的形态，后续改这块时不要退回旧写法。

### 4.1 `waitFor(timeout)` 返回 false ≠ 超时
`bash -c "exit 7"` 的退出码 **7 曾被误写成哨兵值 -1** 并误报 `timeout=true`。
原因：Windows 上进程文件句柄尚未释放时，`waitFor` 可能返回 false 而进程其实已退出。
**现行做法**：`waitFor` 返回 false 之后再问一次 `process.isAlive()`，只有确实还活着
才算超时；随后统一 `awaitQuietly(process, EXIT_WAIT_SECONDS)` 等到真正退出
（内核才会释放重定向句柄、缓冲区才会刷盘）。

### 4.2 不能用应用侧管道读子进程输出
早期实现用两个 daemon 线程读 stdout/stderr，在取消/超时路径上**永久挂起**：
Git Bash 的 `sleep` 是 shell 的**孙进程**，强杀 shell 后它仍持有管道写端，读取线程
永远等不到 EOF；而从另一个线程 `close()` 一个正被 `read()` 的流在 Windows 上
**并不生效**（`FileInputStream.close()` 要先抢同一个监视器，结果一起卡死）。
表现是"cancel 成功但执行线程永挂、槽位不释放"。

**现行做法**：输出**只用内核重定向**（`pb.redirectOutput(File)`），不读管道。
输出上限改为进程结束后 `truncateToBudget` 截断。副作用是输出逐字节忠实
（不再有 `readLine()+newLine()` 的换行改写）。

### 4.3 孙进程句柄导致的测试清理偶发失败 —— ✅ 已解决（测试侧）
即使不读管道，强杀后孙进程若继续存活仍会持有重定向的文件句柄（Windows 上表现为
临时目录删不掉、`CleanupService` 删不干净）。**已做的三层处理**：

1. `ExecutionGate.destroyTree(process, waitForDescendants=true)` 在强杀后短轮询
   （上限 `DESCENDANT_WAIT_MS = 1500ms`）等 `descendants()` 全部消失；
2. `ProcessRunner.launch` 进程退出后再做一次**有界句柄探测**
   （`awaitHandlesReleased`，上限 `HANDLE_RELEASE_WAIT_MS = 3000ms`）；
3. **`ProcessRunnerTest` 的 `@TempDir` 改用 `CleanupMode.NEVER`** —— 这是最终让它稳定的关键。

**为什么最后靠第 3 条解决**：JUnit 的 `@TempDir` 清理发生在**用例刚结束的瞬间**，
而 Windows 上被强杀的 Git Bash 后代可能还要几秒才死。这个窗口**无法从产品侧消除**
（产品已经等进程退出 + 等句柄释放了，但"后代何时死亡"由 OS 决定）。把临时目录回收
交给操作系统，测试就不再随机变红。

**两条走弯路的教训（供接手参考，避免重蹈）**：
- `FileChannel.open(WRITE)` 只验证"能否以写方式打开"，**不等于"能否删除"**；
  Windows 上别人持句柄时它照样成功 —— 所以第 2 条探测其实**不可靠**，
  我曾据此误报"已修复"。
- 手动复现发现：杀掉 `bash.exe` 后，Git Bash 的进程/句柄关系**不再体现为 WMI 的
  ParentProcessId 父子关系**，所以 `ProcessHandle.descendants()` 在 shell 退出后
  可能**枚举不到**真正的 `sleep`。这是"等 descendants 消失"策略的结构性缺口。

> **不是产品功能缺陷**：取消、超时、槽位释放、输出内容都正确
> （`ExecutionCancellationTest` 8/8 稳定通过）。



### 4.4 输出编码不可假设
Windows 上 Git Bash 的 `bash.exe` 在**重定向句柄**上会写 **UTF-16LE**（带 `FF FE` BOM），
且不受 `LANG` / `LC_ALL` / JVM `file.encoding` 影响。严格 `Files.readString` 会抛
`MalformedInputException`，把"脚本跑成功但日志页报错"。

**现行做法**：新增 `util/TextDecoder.readLenient(Path)` / `decodeLenient(byte[])`，
按 BOM 嗅探编码（UTF-8 / UTF-16LE / UTF-16BE）+ `CodingErrorAction.REPLACE`，
绝不抛异常。使用者：`SyntaxCheckService.tryBashN`（读 bash 诊断）、`ProcessRunnerTest`。
另外 `ScriptExecutor.buildEnv` 会补 `LANG=C.UTF-8` / `LC_ALL=C.UTF-8` 默认值
（Linux 生产环境需要）；`pom.xml` surefire 也钉死了 `-Dfile.encoding=UTF-8`。

### 4.5 `CommandSpec.streamOutput` —— 只看退出码就别落盘
`CommandSpec` 新增 `streamOutput` 字段：为 `true` 时**不设置任何重定向**，子进程直接
继承父进程的 stdout/stderr —— 没有输出文件就没有句柄继承，彻底绕开 4.3 的问题。

- 用它的：`TenantService.testConnectivity` 的 kinit / klist（只看退出码）。
- **不要**用它：`SyntaxCheckService.tryBashN` —— `bash -n` 的诊断信息在 stderr 上，
  必须读回来给用户看，所以它传显式 `outPath` / `errPath` 并用 `TextDecoder` 读回。
  （曾一度误改成 `streamOutput=true`，导致 `SyntaxCheckTest` 拿不到错误信息而失败。）


> 生产目标是 Linux，以上 4.1–4.4 中的 4.2/4.3/4.4 主要是 Windows 开发环境的坑；
> 但 4.1 和 4.2 的写法在 Linux 上同样是更正确的（忠实输出 + 不依赖管道消费）。


---

## 5. 尚未开始的重构（剩余 3 条）

### ✅ 已完成（第 8 条）：#5 PreCheck 策略族收敛
**新增**：`service/precheck/AbstractJsonListPrecheckStrategy.java`
- `commands` / `files` / `writableDirectories` 三个策略**逐字重复的 `items()`**（各 14–15 行）
  上提到基类，子类只留 `configKey()` + `check()`。
- `PrecheckStrategy`: `type()` → **`configKey()`**；新增 `default displayName(String item)`
  （默认 `key:item`）。`KerberosPrecheckStrategy` 覆写为 `"Kerberos"`，于是
  **`PrecheckService` 里的 `type.equals("kerberos")` 特判被删除** —— 显示名从两处来源收敛为一处。
- 返回类型 `Map<String,Object>` → **`PrecheckReport(ok, skipped, message, results)`** +
  `CheckResult(name, ok, message)` record。调用方不再写 `Boolean.TRUE.equals(map.get("ok"))`。
  同时删掉**死代码** `PrecheckService.CheckResult`（定义了却全程用 `toMap` 手工拼）。
- `PrecheckStrategyRegistry`: `knownTypes()`/`byType()` → `knownKeys()`/`byKey()`。
- `PrecheckController`: 改 `@RequiredArgsConstructor`，返回类型化 record，
  抽出 `resolveTenant(Object)` 去掉嵌套 try/catch。
- **前端零影响**：record 的字段名（`ok`/`skipped`/`message`/`results`/`name`）与旧 Map 键逐一对应。
- 顺手修掉测试里写死的 `/tmp`（Windows 上不存在）与手工拼 JSON 的脆弱写法，
  改用 `System.getProperty("java.io.tmpdir")` + `ObjectMapper` 序列化。

### ⚠️ #10 配置收口 —— **只开了个头，已回滚，不要当成半成品**
我在 `ScriptBoxProperties` 里一度删掉 `maxArtifactTotalBytes` 并加了
`maxBatchRows` / `maxBatchConcurrency`，但**没有接线到调用方**（`BatchService` 仍用
硬编码 `MAX_ROWS = 200` 与 `newFixedThreadPool(8)`）。这等于制造了**新的死配置**——
恰好是 #10 要消灭的东西。**已完整回滚**，当前文件是一致状态。

接手时从这些点入手（全部已核实）：

| 问题 | 位置 |
|---|---|
| 默认超时 `600` 出现在 **6 处** | `db/schema.sql:20`、`ScriptBoxProperties.defaultTimeoutSeconds`、`ScriptExecutor`（已改用 props）、`ScriptController:85,117,159`、`ScriptPackageService:149`、`ScriptService:108`、`ExecutionContext.Builder:72` |
| `maxArtifactTotalBytes` 是**死配置**（零读取点） | `ScriptBoxProperties`；要么在 `ArtifactService.scanAndRegister` 真正接线，要么删除 |
| `MAX_ROWS = 200` + 并发 `8` 硬编码 | `BatchService:33,82-83,130-131,136`（且行数校验**重复两遍**） |
| 手写三套配置源优先级 | `CleanupService:77-92` 的 `settings.getInt(K_*, props.getRetention*)` |
| `86_400_000L` 魔法数 | `CleanupService:138,221` → `Duration.ofDays(n).toMillis()` |
| `application.yml` **缺 `max-concurrent`** | 该键当前根本不在 YAML 里，只在 Java 默认值 |

建议做法：抽 `RetentionPolicy`（集中"`system_setting` 覆盖 > YAML > 代码默认"的优先级
与天数换算），并给 `BatchService` 的边界值加上带校验的配置项，**接线完成后再加字段**。

### 剩余两条（未开始）

| 项 | 内容 | 关键落点 |
|---|---|---|
| **#1** | `ScriptExecutor` 编排拆解（885 行 → Pipeline：`ExecutionPreparer` + `CommandBuilder` + `ExecutionFinalizer`） | `executor/ScriptExecutor.java`（当前已比原来小一些：#4 已抽走 `resolveParams`/`upsertHistory`/`materializeScript`，但 `prepareContext` 仍有 ~100 行、`finalizeExecution` 仍 ~90 行） |
| **#5** | PreCheck 策略族收敛：3 份逐字重复的 `items()` 提取到 `AbstractJsonListPrecheckStrategy`；删掉 `PrecheckService` 里硬编码的 `type.equals("kerberos")` 分支（改用 `strategy.displayName(item)`）；删掉**死代码** `PrecheckService.CheckResult`（定义了但 `run()` 全程用 `toMap` 手工拼）；`run()` 返回类型化的 `PrecheckReport` 取代 `Map<String,Object>` + `Boolean.TRUE.equals(get("ok"))` | `service/PrecheckService.java`、`service/precheck/*` |
| **#8** | DTO 边界收敛：全项目 **90 处** `Map<String,Object>`。已顺手类型化了 `ExecutionController` 的 `RunningExecutionView` / `ExecutionStateView`（**字段名逐一对应旧 Map 键，前端零改动**）。剩余重点：`ScriptExecutor.preview()` 的 15 键 Map、`PrecheckService.run()`、`BatchService.summarize()`、`ArtifactService.listView()`、`FileUploadService.savePending()`、`AdminController` 的 `@RequestBody Map`。另：`HistoryService.deriveStatus` 与 `ExecutionStatus.of()` 是同一状态归一化的两次实现，应合一 | 多个 service/controller |
| **#9** | 异常体系落地：`BusinessErrorCode` 有 **31 个枚举但全项目只有 2 个抛出点**（都在 `StoragePathService`），其余全是裸 `IllegalArgumentException`/`IllegalStateException` → 前端 `code` 永远 1，无法分流；`AdminController` 还自建局部 handler 把错误码降级成 `"PREVIEW_EXPIRED: "` 字符串前缀 | `exception/*`、各 service/controller |
| **#10** | 配置与常量收口：默认超时 `600` 出现在 **6 处**；`CleanupService.getInt(K_*, props.get*)` 手写三套配置源优先级；`maxArtifactTotalBytes` 是**死配置**（零读取点）；`application.yml` **缺 `max-concurrent`**；`BatchService` 硬编码并发上限 8 / `MAX_ROWS` 200（且行数校验重复两遍） | `config/ScriptBoxProperties.java`、`application.yml`、多个 service |

---

## 6. 变更文件清单

**新增（6）**
```
src/main/java/com/bigdata/scriptbox/executor/CommandExecutor.java
src/main/java/com/bigdata/scriptbox/executor/CommandSpec.java
src/main/java/com/bigdata/scriptbox/executor/CommandResult.java
src/main/java/com/bigdata/scriptbox/service/ExecutionGate.java
src/main/java/com/bigdata/scriptbox/util/TextDecoder.java
JAVA_MAJOR_REFACTOR_REVIEW.md   （上一轮的分析报告，10 条建议的完整依据）
JAVA_REFACTOR_HANDOFF.md        （本文件）
```

**删除（1）**
```
src/main/java/com/bigdata/scriptbox/service/RunningExecutionRegistry.java   （被 ExecutionGate 取代）
```

**主代码修改（21）**
```
pom.xml
config/ScriptBoxProperties.java
controller/{Batch,Execution,GlobalVariable,History,Preset,Scenario,Script,ScriptVersion,System,Tenant}Controller.java
executor/{ExecutionContext,ProcessRequest,ProcessRunner,ScriptExecutor}.java
service/{CleanupExecutor,CleanupService,ScenarioService,ScriptPackageService,ScriptService,SyntaxCheckService,TenantService}.java
service/precheck/CommandExistsPrecheckStrategy.java
```

**测试修改（6）**
```
ConcurrencyGateTest.java      （registry → ExecutionGate）
ExecutionCancellationTest.java（registry → ExecutionGate；等待条件不再要求 RUNNING 状态）
ProcessRunnerTest.java        （构造器签名；cancel 用例改为先 acquire 许可）
DryRunTest.java               （shell 名不再写死 "bash"）
PrecheckServiceTest.java      （getMapper() → savePrecheckConfig(...)）
StoragePathServiceTest.java   （路径断言平台无关）
```

---

## 7. 给接手的建议顺序

1. **基线已全绿**（156/156），可以直接开始下一项。
2. 建议先做 **#5**（PreCheck）—— 独立子系统、改动局部、**不碰执行主链路**、收益明确。
3. 然后 **#1**（ScriptExecutor 拆解）—— 收益最大但风险最高。
   ⚠️ **不要动**：`ProcessRunner` 的内核重定向（见 §4.2，不要退回读管道）；
   `StoragePathService` 的路径安全门；PreCheck 的 Strategy+Registry **外层架构**
   （只做内部收敛，别上规则引擎）。
4. **#8 / #9 / #10** 可并行推进。注意 #9 会改 `ApiResponse` 的响应契约，
   需要和前端 `frontend/src/api/*.js` 对一次（前端目前只用 `code` + `message` + `data`）。


## 8. 上一轮遗留的文档

`JAVA_MAJOR_REFACTOR_REVIEW.md`（仓库根目录）是**重构前的分析报告**，含 10 条建议的
完整问题描述、代码行号引用、收益/风险/工作量评估、分阶段建议。本文件是它的执行进度对照。
**两份文档建议一起保留**：前者是"为什么改"，后者是"改到哪了 + 怎么接着改"。
