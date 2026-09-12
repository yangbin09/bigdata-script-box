# 前端优化报告（Vue 3 + Vite + Element Plus）

> 范围：`frontend/` 全部源码（9 个 api 模块、7 个 view、9 个 component、7 个 util）。
> 目标：在**不改变产品行为**的前提下修掉真实缺陷、消除重复实现、降低无效计算与包体。
> 验证：`npm run build` 通过；入口 chunk 1256.72 kB → **1117.92 kB**（gzip 404.98 kB → **368.84 kB**）。
> 说明：本机没有 `mvn`，因此未做「启动后端 + 浏览器点检」的端到端验证，留给发布前回归（见文末）。

---

## 一、根因级修复：API 响应契约（一个 bug 类，5 处表现）

`api/http.js` 的响应拦截器返回的是整个 `{code, message, data}` 包装体，而 `api/*.js` 里每个函数又写了
`.then((r) => r.data)`，于是调用方再写 `r?.data` 时**看起来正确、实际永远是 undefined**。

处理方式（根因，而不是逐个打补丁）：

1. `api/http.js`：JSON 响应统一解包并**只返回 payload**（`code !== 0` 时 reject），text/blob 响应返回原始 body；
   并在文件头写清契约 ——「调用方不得再取一次 `.data`」。
2. 9 个 `api/*.js` 模块：删除全部 `.then((r) => r.data)`（约 50 处），逻辑变成 `http.get(...)` 直出。
3. 修正所有二次解包的调用点。

被这一个 bug 直接打坏、现已恢复的功能：

| 位置 | 症状 | 修复 |
| --- | --- | --- |
| `CleanupPanel.vue` 预览 | `r?.code !== 0` 恒真 → **每次预览都报「预览失败: undefined」**，抽屉永远打不开，清理流程不可用 | `const data = await previewCleanup(...)`，只在 `!data` 时报错 |
| `CleanupConfirmDialog.vue` 执行 | 同样的恒真判断 → 清理**成功也报「执行失败: undefined」**，且从不 emit `done`（报告抽屉/历史刷新全失效） | 直接使用返回的 report，错误交给拦截器 |
| `HistoryView.vue` 结果页 | `readResult()` 已解包，又取 `r?.data` → 结果 Tab 恒为空 | 直接使用返回值 |
| `HistoryView.vue` / `ExecutionResultPanel.vue` 产物页 | `listArtifacts()` 已解包，又取 `.data` → **产物列表永远为空** | 直接使用返回数组 |
| `ScriptsView.vue` / `ScenariosView.vue` / `TenantsView.vue` 删除确认 | `related-counts` 二次解包 → 关联影响提示永远不显示（每次都白跑一次请求） | 直接使用返回值 |

## 二、其它确认的功能缺陷

| # | 位置 | 问题 | 修复 |
| --- | --- | --- | --- |
| 1 | `api/executions.js` + `ExecuteView.vue` | 实例默认 timeout 60 s，而 `POST /api/executions` 是**同步阻塞**到脚本结束（默认 600 s）。任何跑过 1 分钟的脚本都会在前端被判失败，后端却仍在跑 | `execute/rerun` 默认不设客户端超时；`ExecuteView` 按 `(timeoutSeconds + 60) * 1000` 显式传入 |
| 2 | `ExecuteView.vue` `onMounted` | `onUnmounted` 写在 `await` 之后，Vue 已无当前实例，钩子被静默丢弃 → `resize` 监听与 1 s 计时器泄漏 | 顶层 `onUnmounted` 统一清理监听 + `elapsedTimer` |
| 3 | `ParamForm.vue` + `ExecuteView.vue` | `:initial-values="lastParamsForScript"` 每次渲染都构造新对象，加上对 props 的 deep watch → 表单被重新播种，**用户填到一半的参数被清空**（例如上传文件后触发重渲染） | 父组件改为稳定的 `computed`；`ParamForm` 改为比较内容签名而非引用 |
| 4 | `ParamForm.vue` | `file` 类型参数没有分支，落到兜底 `<el-input>`，与上传区**重复渲染** | 表单不再渲染 file 参数（上传控件归调用方） |
| 5 | `HistoryView.vue` | 产物表格读 `row.size`，DTO 字段是 `sizeBytes` → 大小列恒为 `-` | 改读 `sizeBytes` |
| 6 | `HistoryView.vue` | 结果/产物的「刷新」按钮调用的函数被 `*LoadedFor === id` 挡住（且失败时也写标记）→ 永远不能刷新/重试 | 增加 `force` 参数，标记只在成功时写入 |
| 7 | `HistoryView.vue` | 点行详情串行 3 次请求、且没有竞态保护（A 行的日志可能画进 B 行抽屉） | `Promise.all` + `current.id` 竞态守卫 |
| 8 | `HistoryView.vue` | 「按快照重跑」只用新 executionId 弹了个 toast，然后跳到执行中心什么也不显示 | 重跑后刷新列表并直接打开该次执行的详情抽屉 |
| 9 | `LogPane.vue` | `compiledRegex` 是普通变量，切换「正则」开关不会让任何 computed 失效 → 过滤/高亮停留在旧模式 | 编译结果改为 `computed` |
| 10 | `LogPane.vue` | 高亮时先在**转义后的 HTML** 上匹配：`& < > "` 永远不高亮，命中实体内部还会破坏标记（`&quot;` → `&<mark>quot</mark>;`） | 先在原文 split，再对每段转义，奇数段包 `<mark>` |
| 11 | `LogPane.vue` | `executionId` 声明为必填却从未使用；`destroy-on-close=false` 导致跨执行残留搜索条件 | 用 `executionId` 变化重置搜索/过滤/上下文 |
| 12 | `ScriptEditView.vue` | 回滚成功提示读 `res.versionNo`，后端返回的是 `{ newVersion: {...} }` → 提示「已回滚为 vundefined」 | 读 `res.newVersion?.versionNo` |
| 13 | `ScriptEditView.vue` + `api/extras.js` | `POST /precheck` 不传 body，而控制器是 `@RequestBody Map` → Spring 返回 415，「立即检查」从未成功过 | `runPrecheck(id, { tenantId })` 始终发 JSON body |
| 14 | `ScriptEditView.vue` | `if (syntaxChecking) return` + await 后写结果：旧请求挡住新请求、旧结果覆盖新结果、手动点击被丢弃 | 单调序号 token，只有最新一次可以写入 |
| 15 | `ScriptEditView.vue` | 语法检查定时器卸载时不清 → 离开页面后仍发请求 | `onBeforeUnmount` 清理 |
| 16 | `ScriptEditView.vue` | 切换 `?id=`（同一路由）不会触发 `onBeforeRouteLeave`，**未保存的编辑被静默丢弃** | 监听 `route.query.id`，脏数据时二次确认，取消则回滚 query |
| 17 | `ScriptEditView.vue` / `ScenariosView.vue` / `TenantsView.vue` / `SettingsView.vue` | `ElMessageBox.confirm` 未 catch，点「取消」产生 unhandled rejection | 统一 `.catch(() => null)` + 提前 return |
| 18 | `ScenariosView.vue` | 新建场景后 `form.id` 未回填，若 steps 保存失败，再点保存会**创建出第二个场景** | 创建成功后立即 `form.id = id` |
| 19 | `ScenariosView.vue` | 详情加载失败时 `steps.value = []` 仍打开抽屉，保存会清空已持久化的步骤 | 失败即报错并保持抽屉关闭 |
| 20 | `ScenariosView.vue` | 步骤内切换脚本后 `presetId` 仍指向旧脚本的方案并被持久化 | `@change` 清空 presetId |
| 21 | `SettingsView.vue` | 敏感全局变量列表返回 `******`，编辑保存时原样回写 → **把真实密钥覆盖成掩码** | 只在用户确实重填时才提交 `variableValue` |
| 22 | `TenantsView.vue` | 测试抽屉无 loading，且 A 租户的迟到响应会覆盖 B 的结果 | `testing` + `v-loading` + 租户 id 守卫 |
| 23 | `TenantsView.vue` | `toggleEnabled` 从未被模板引用，租户在 UI 上无法启停（场景页却按该标志过滤） | 状态列改为 `el-switch` 并接上处理器 |
| 24 | `CleanupPanel.vue` | 「耗时」列读 `row.elapsedMs`，`cleanup_history` 无此字段 → 恒为 `0.0s` | 删除该列 |
| 25 | `CleanupReportDrawer.vue` | 分类表的「跳过/失败」硬编码 0（后端只有扁平列表）→ 死列；「预计释放」与「实际释放」打印同一个值 | 删除死列与重复行 |
| 26 | `CleanupPreviewDrawer.vue` | 只统计 `skippedRunning`，忽略 `skippedSymlink`/`skippedEscape` → 只有跳过期时显示「没有任何内容需要清理」 | 合并三类跳过计数参与空态判断 |
| 27 | `style.css` | `--sb-text-1` / `--sb-text-muted` / `--sb-bg-soft` 被 8 个组件引用但从未定义 → 声明在计算值阶段失效，弱化文字静默继承主色，卡片底色透明 | 在 `:root` 补齐三个 token |
| 28 | `utils/format.js` | `formatTimestamp` 对数字走 `toISOString()`（UTC），对字符串按本地时钟输出 → 同一时刻两种显示；缺位不补零 | 统一按本地时区格式化并补零 |

## 三、结构性重构（消除重复实现）

新增 4 个共享模块，并在所有调用点替换掉各自的私有副本：

| 新模块 | 内容 | 替换掉的重复实现 |
| --- | --- | --- |
| `utils/params.js` | `parseOptions` / `serializeOptions` / `parseVisibilityRule` / `evaluateRule` / `visibilityMap` | `ParamForm.vue` 与 `ScriptEditView.vue` 各一份 options 解析；两份**互相矛盾**的 `visibleWhenJson` 校验（编辑器拒绝引用未声明参数，运行时却接受） |
| `utils/clipboard.js` | `copyText` / `downloadText` | `HistoryView` / `ExecutionResultPanel` / `LogPane` 三份剪贴板+Blob 下载（顺带修掉 `click()` 后立刻 `revokeObjectURL` 的时序问题，并补上 http 环境下 `execCommand` 兜底） |
| `utils/status.js` | `statusIcon(status)` | `HistoryView` 与 `ExecutionResultPanel` 逐字重复的 icon switch（两者都把 CANCELLED/RUNNING 落到 success 默认值 → 已取消显示绿勾） |
| `utils/cleanup.js` | 清理结果 label/tag/class + 跳过计数聚合 | `CleanupPanel` 与 `CleanupReportDrawer` 各一份结果映射（文案还不一致） |

同时：

- `utils/labels.js` 增加 `STATUS_CLASS` / `STATUS_HEADLINE` / `statusFromString` / `labelOf` / `tagTypeOf`，
  删除死代码 `STATUS_CHIP`、`RESULT_LABEL`、`ENABLED_LABEL`、`SENSITIVE_LABEL`、`statusOfScript`、`PARAM_TYPE_PLACEHOLDER`；
  `ExecuteView` 里私有的 `recentTagType`/`recentLabel` 两个映射表随之删除。
- `utils/storage.js` 增加 sessionStorage 封装、`takeSessionItem` 与 `KEYS` 常量，
  替换 `HistoryView`/`ExecuteView` 里裸的 `sessionStorage` + 手写 `JSON.parse`，以及散落的 `'sb.settingsTab'` 等字符串字面量。
- `utils/format.js` 增加 `isValidJson` / `toDateString`，供编辑器校验与日期快捷筛选复用。

## 四、性能优化

1. **包体**：`main.js` 不再 `import * as ElementPlusIconsVue` 全量注册 293 个图标，改为只注册实际用到的 38 个
   （`import *` + 动态循环会让 tree-shaking 完全失效）。
   入口 chunk **1256.72 kB → 1117.92 kB（−139 kB）**，gzip **404.98 kB → 368.84 kB（−36 kB）**。
2. **日志面板**：`LogPane` 原来对同一份日志跑 3~4 遍匹配（可见行、匹配数、可见文本、高亮 HTML），
   现在一次遍历得到匹配集合，其余全部派生；正则/literal 编译结果也改为 computed（键入时不再重复编译）。
   顺带删掉恒为 `'gi'` 的 `flags` 拼接。
3. **整页重算**：`HistoryView` 的 `paramsPretty`/`snapshotText` 由「模板里每次渲染都 JSON.parse + stringify」
   改为 `computed`；`copyParams` 复用同一结果。
4. **编辑器脏检查**：`ScriptEditView.isDirty` 原实现每次响应式更新都 `JSON.stringify` 整份脚本正文（最大 1 MB），
   改为 watcher 维护的布尔标记，敲一个字符不再做一次全量序列化。
5. **编辑器可见性校验**：`visibleWhenError` 原为「每个参数每次渲染调用两次，且每次重建一个 Set」，
   改为 `computed` 的「参数名集合 + 每个参数的错误 Map」。
6. **N+1 阻塞**：`ScriptsView`（参数计数）与 `ScenariosView`（步骤数）原先在 `loading` 期间串行等待全部逐行请求，
   现在表格先渲染，附加数据后台补齐；`HistoryView` 首次加载的 scripts/tenants 也不再串行阻塞历史列表。
7. **场景页 deep watch**：`watch(steps, ..., { deep: true })` 会在改 presetId / 开关时也触发（并重复 `openEdit` 的显式调用），
   改为只监听「被引用的脚本 id 序列」。

## 五、删除的死代码

- `labels.js` 6 个无引用导出（见上）。
- `CleanupConfirmDialog` 空处理器 `onTokenChange`（每次输入都被调用）及其模板绑定。
- `CleanupReportDrawer` 的「预计释放」重复行、分类表「跳过/失败」死列。
- `CleanupPanel` 的「耗时」死列。
- `ExecuteView` 中一段无副作用的「持久化参数」循环（`collectParams()` 已经把文件参数换成 serverPath 后又被二次覆盖）；
  同时**文件参数不再写入 last-params**（否则下次打开会静默复用一次性的服务端路径）。
- `ParamForm` 未被任何调用方使用的 `hiddenParams()`、`LogPane` 未使用的 `executionId` 必填约束，
  `ScriptEditView` 恒真的 `v-else-if="p.type !== 'select'"` 与保存时必被覆盖的 `sortOrder` 初始化。
- 各 view 里 `const fmtBytes = formatBytes` 这类零收益别名（4 个清理组件），改为 import 时重命名。

## 六、刻意没做的事（建议的后续项）

这些改动收益存在但要动架构或引入构建期成本，本轮没有做，避免与"修缺陷"混在一起：

1. **Element Plus 按需引入**（`unplugin-vue-components` + `unplugin-auto-import`）：入口 1.1 MB 里绝大部分是它。
   需要新增 devDependencies（本机装包受限），且模板无需逐个 import 的便利会消失。
2. **CRUD 抽屉抽象**：`ScenariosView` / `TenantsView` / `SettingsView` / `ScriptsView` 的
   `openCreate/openEdit/submitForm/confirmDelete/refresh` 有 ~80% 重复，可抽 `useCrudDrawer()` 组合式函数。
3. **`CleanupPreviewDrawer` 四个分类区块**（约 120 行重复 DOM）抽成 `CleanupCategoryTable`。
4. **列表接口瘦身**：`GET /scenarios` 返回 `stepCount`、`GET /scripts` 返回 `paramCount`，
   可彻底去掉前端两处逐行 N+1（本轮只做了「不阻塞首屏」）。
5. **历史详情页对 RUNNING 执行的轮询**（当前只在打开时读一次 stdout/stderr）。
6. **`vite.config.js` 注释与现状不符**：`manualChunks: undefined` 注释说"单 chunk"，但路由懒加载其实已经产出了
   多 chunk（`ExecuteView-*.js`、`HistoryView-*.js` …）。建议要么改注释，要么显式把 `vue`/`element-plus` 拆成 vendor chunk。
7. `listScripts()` / `listTenants()` 在 5 个 view 里各请求一次，可做带失效的请求级缓存。

## 七、验证情况

已做：

- `npm run build` 通过（多轮，覆盖全部改动）；产物 chunk 列表与体积符合预期。
- 包内 293 个图标与实际 `<Xxx />` 模板标签做了脚本比对，确认没有"标签存在但未注册"的图标。
- 全仓 grep 复查：不再存在 `r?.data` / `.then(r => r.data)` 形式的二次解包；
  `sessionStorage` / `localStorage` 只在 `utils/storage.js` 出现；
  已删除的 `labels.js` 导出无残留引用。

未做（建议发布前执行）：

1. `mvn clean package`（会跑 `npm ci` + `vite build` 并把 dist 注入 JAR）。
2. 浏览器点检清单：执行中心（长脚本 > 60 s 不再前端超时、取消执行、文件参数、批量预览）；
   执行历史的「结果 / 产物 / 刷新 / 按快照重跑」；脚本编辑（切换脚本的未保存提示、语法检查、回滚提示、执行前检查）；
   场景保存与删除提示；租户测试抽屉与状态开关；设置页敏感变量的保存（确认未被掩码覆盖）；
   **清理流程的预览 → 确认 → 报告**（修复前这条链路完全不可用）。
