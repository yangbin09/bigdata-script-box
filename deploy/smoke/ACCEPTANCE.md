# 验收清单（供自动化/第三方执行）

> **用途**：这份清单是**给执行者机械跑的**，不是给人读的设计文档。
> 每条都是「命令 → 期望值 → PASS/FAIL」，期望值来自**人的意图**，不是来自实现。
>
> **重要**：如果某条不通过，**不要修改清单、不要去改代码**，直接把「实际观测」和原始输出贴回来。
> 本清单的作者需要原始证据来判断是回归还是期望值写错了。

## 0. 执行环境与前置条件

| 项 | 值 |
| --- | --- |
| 被测地址 | `http://127.0.0.1:5199`（或部署后的 `http://8.163.99.17`） |
| 后端 | 同一个 origin 下的 `/api/**` |
| 环境标记 | 顶栏显示 `MOCK`（Mock 模式，无 Hadoop/Kerberos） |

**这个环境的已知事实（不要当成 bug 报）**：

1. **只有 1 个启用租户**（`mock-hive`）。所以执行抽屉里的租户**必须显示成一行文本**，不是下拉框。
2. **没有 Hadoop**。脚本日志里出现 `would run: hdfs dfs ...` 是**预期输出**。
3. **有 4 个内置脚本**：成功示例 / 失败示例 / stderr 示例 / 超时示例。
4. **超时示例的 timeout 是 3 秒**，所以它约 3 秒后变「执行超时」，这是**预期**。
5. `favicon.ico` 请求 **500** 是**已知历史问题**，不计入失败。
6. 页面加载后顶栏角标可能显示「运行中」——测试间要**等 3 秒**让状态收敛。

## 1. 导航与路由（P3）

| # | 操作 | 期望 | 判定 |
| --- | --- | --- | --- |
| 1.1 | 打开 `#/`，读顶栏导航项文本 | 恰好 3 项且顺序为 `工作台` \| `历史` \| `设置` | PASS/FAIL |
| 1.2 | 打开 `#/`，读页面 `h2` | 等于 `工作台` | PASS/FAIL |
| 1.3 | 访问 `#/scripts` | 最终 `location.hash` = `#/`，`h2` = `工作台` | PASS/FAIL |
| 1.4 | 访问 `#/tenants` | 最终 hash = `#/settings?tab=tenants` | PASS/FAIL |
| 1.5 | 访问 `#/scenarios` | 最终 hash = `#/settings?tab=scenarios` | PASS/FAIL |
| 1.6 | 访问 `#/cleanup` | 最终 hash = `#/settings?tab=cleanup` | PASS/FAIL |
| 1.7 | 访问 `#/settings`，读 tab 文本 | 含 `全局变量`、`租户与认证`、`数据清理`、`场景编排` 四个 | PASS/FAIL |

## 2. 高频路径：「跑上次」1 步执行（P0-B）

这是本次最重要的行为变更。**判定标准是"1 次点击产生 1 次执行请求、且结果为成功"**。

| # | 操作 | 期望 | 判定 |
| --- | --- | --- | --- |
| 2.1 | 在 `#/` 找到标题为 `成功示例` 的卡片，点它右下角的 `跑上次` | 抽屉打开，且**不需要再点任何按钮** | PASS/FAIL |
| 2.2 | 上一步后统计 `POST /api/executions` 次数 | **恰好 1 次**（不能是 2 次） | PASS/FAIL |
| 2.3 | 上一步请求体的 `params` | **包含 `"name"`**（不能是 `{}`） | PASS/FAIL |
| 2.4 | 等待抽屉出现结果面板 | 出现，状态headline = `执行成功`，tags 含 `Exit 0` | PASS/FAIL |
| 2.5 | 切到 stdout tab | 文本包含 `[success] name = world` | PASS/FAIL |
| 2.6 | 切到 stderr tab | 文本为 `无 stderr 输出` | PASS/FAIL |
| 2.7 | 切到 参数 tab | 是 JSON 且含 `"name": "world"` | PASS/FAIL |
| 2.8 | 读抽屉底部按钮 | 恰好含 `关闭`、`查看历史`、`修改参数`、`再次执行` | PASS/FAIL |
| 2.9 | 点 `修改参数` | 结果面板消失，底部重新出现 `执行` 按钮 | PASS/FAIL |

> 2.1–2.3 一起构成回归防线：曾经出现过"点一次发两次请求""参数被提交成空对象""脚本里 `${NAME:-world}` 静默回退默认值"三个缺陷。

## 3. 四种执行结果（日志必须真实显示）

对以下每个脚本：从 `#/` 点卡片 → 等抽屉出现参数表单 → 点 `执行` → 等结果面板出现。

| # | 脚本 | 期望 状态/Exit | 期望 stdout 含 | 期望 stderr 含 |
| --- | --- | --- | --- | --- |
| 3.1 | 成功示例 | `执行成功` / `Exit 0` | `[success] script started` | `无 stderr 输出` |
| 3.2 | 失败示例 | `执行失败` / `Exit 1` | `step 1 ok` | `ERROR: simulated failure on stderr` |
| 3.3 | stderr 示例 | `执行成功` / `Exit 0` | `[stdout] hello` | `[stderr] warning` |
| 3.4 | 超时示例 | `执行超时` / `Exit -1`，tags 含 `超时` | `starting long sleep` | `无 stderr 输出` |

**3.3 的关键**：stdout 与 stderr **各自都有内容且不互相污染** —— 这是"两个流分开落盘"的验证。
**3.4 的关键**：超时前已产生的输出**必须保留**（不能因为被杀就变空）。

## 4. 读取失败 ≠ 空日志（必须区分）

| # | 操作 | 期望 | 判定 |
| --- | --- | --- | --- |
| 4.1 | 把 `**/api/executions/*/stdout` 拦成 HTTP 500，然后执行 `失败示例` | stdout tab 显示 `读取 stdout 失败：` 开头的信息 | PASS/FAIL |
| 4.2 | 同上，检查是否出现按钮 `重新读取 stdout` | 出现 | PASS/FAIL |
| 4.3 | 同上，stderr tab | 仍正常显示 `ERROR: simulated failure on stderr`（一个流失败不影响另一个） | PASS/FAIL |

> 反向断言：**4.1 时绝不能显示 `无 stdout 输出`** —— 那意味着读取失败被伪装成了"真的没有输出"。

## 5. 参数区简化（P0-B / P1-A）

| # | 操作 | 期望 | 判定 |
| --- | --- | --- | --- |
| 5.1 | 点 `成功示例` 卡片（不点跑上次），等 3 秒 | 出现「上次成功：… 分钟前」一行 + 按钮 `用这套参数` | PASS/FAIL |
| 5.2 | 读参数区 | 出现 `修改其他 N 个参数`（N ≥ 1），即默认值参数被折叠 | PASS/FAIL |
| 5.3 | 读参数区，搜索文本 `撤销` | **不得出现** `撤销 (5s)` 这类倒计时按钮 | PASS/FAIL |
| 5.4 | 读参数区，检查是否存在"默认参数/上次执行/指定方案/未提交草稿"四个徽章 | **不得存在** | PASS/FAIL |
| 5.5 | 点 `修改其他 N 个参数` | 折叠的参数展开显示 | PASS/FAIL |

## 6. 历史页：按脚本 + 按天（P1-B）

| # | 操作 | 期望 | 判定 |
| --- | --- | --- | --- |
| 6.1 | 打开 `#/history` | 副标题形如 `最近 7 天 · N 个脚本 · 共 M 次执行` | PASS/FAIL |
| 6.2 | 读概览表 | 每行一个脚本；列含 `脚本`、`最近一次`、`近 7 天`、`成功/失败`、`操作` | PASS/FAIL |
| 6.3 | 读「近 7 天」列的柱子数量 | ≥ 1（当天有执行就至少 1 根） | PASS/FAIL |
| 6.4 | 点某根柱子 | 弹出该脚本该天的执行记录列表 | PASS/FAIL |
| 6.5 | 检查页面上是否直接暴露 5 个筛选器 | **不得直接暴露**；应折叠在 `高级筛选 / 全部记录` 里 | PASS/FAIL |
| 6.6 | 展开 `高级筛选`，点 `查询` | 出现记录表 | PASS/FAIL |
| 6.7 | 点概览某行的 `跑上次` | 跳回 `#/`，且**自动执行**（不需要再点执行） | PASS/FAIL |

## 7. 控制台与网络（回归防线）

| # | 操作 | 期望 | 判定 |
| --- | --- | --- | --- |
| 7.1 | 完整走一遍 2.1–2.9 + 3.1–3.4，收集 console error | 除 `favicon.ico 500` 外**无其它 error** | PASS/FAIL |
| 7.2 | 检查是否有 `ReferenceError` / `Cannot access ... before initialization` | **不得出现** | PASS/FAIL |
| 7.3 | 检查执行过程中 `GET /api/executions/{id}/log-tail` 的状态码 | 200（**不得是 404**） | PASS/FAIL |
| 7.4 | 检查 `GET /api/executions/{id}/state` 是否被调用 | 被调用且返回 200 | PASS/FAIL |

> 7.3 的 404 曾经出现过：执行刚提交、history 行还没落库时前端就拉日志。
> 后端已把"运行中但没有行"改成 200 空体；**未知 executionId 仍必须是 404**（这条也要抽查：`GET /api/executions/900000999/log-tail?stream=stdout` 期望 404）。

## 8. 后端接口抽查（可 curl）

| # | 请求 | 期望 |
| --- | --- | --- |
| 8.1 | `GET /api/history/plan?scriptId=1` | `code=0`；若该脚本成功跑过，`data.executionId` 非 null 且 `data.parameters.name` 存在 |
| 8.2 | `GET /api/history/plan?scriptId=999999` | `code=0`（**不是 404/500**），`data.parameters` 为空对象 |
| 8.3 | `GET /api/history/digest?days=7` | `code=0`；每项含 `scriptId`、`total`、`succeeded`、`failed`、`days[]`、`failureRate` |
| 8.4 | `GET /api/executions/900000999/log-tail?stream=stdout` | **HTTP 404**（未知执行必须 404，不能被当成空日志） |
| 8.5 | `GET /api/history/plan?scriptId=1&tenantId=null` | **HTTP 400 是预期的**（Spring 无法把字符串 `null` 转 Long）—— 这条用来确认前端不会再发出这种请求；若前端发出则 7.x 会看到 500 |

## 9. 判定汇总模板

跑完请**原样贴回**下面内容（不要只写"全部通过"）：

```
执行环境: <地址>  时间: <本地时间>
1 导航:      PASS/FAIL  <实测值>
2 跑上次:    PASS/FAIL  POST 次数=<n>  请求体=<...>  状态=<...>
3 四脚本:    PASS/FAIL  逐个: 成功=<状态/Exit/stdout首行> 失败=<...> stderr=<...> 超时=<...>
4 读失败:    PASS/FAIL  stdout 面板文本=<...>
5 参数简化:  PASS/FAIL  折叠数=<n> 撤销按钮=<有/无> 徽章=<有/无>
6 历史:      PASS/FAIL  副标题=<...> 柱子数=<n>
7 控制台:    PASS/FAIL  error 列表=<...>
8 接口:      PASS/FAIL  各条 HTTP 码与关键字段=<...>

失败项原始证据（截图路径 / 控制台原文 / 接口原文）:
<粘贴>
```

## 10. 明确不在本次范围内（不要报为失败）

- `favicon.ico` 500
- 场景编排的**功能**（它只是从顶级导航降级到设置内的子页，功能未改）
- 脚本编辑页（`ScriptEditView`）的内部结构
- 移动端/窄屏布局
- 性能指标
