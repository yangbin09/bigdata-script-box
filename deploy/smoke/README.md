# 页面冒烟测试

一次调用覆盖全部页面级功能检查，用于**每次部署后回归**。

## 怎么跑

MCP 的 `browser_run_code_unsafe` 只允许读取 `C:\Users\16922\.playwright-mcp` 和 home 目录下的文件，所以先把仓库里的正本同步过去，再用 `filename` 调用：

```powershell
Copy-Item .\deploy\smoke\browser-smoke.js $env:USERPROFILE\.playwright-mcp\ -Force
```

```
browser_run_code_unsafe(filename: "C:\\Users\\16922\\.playwright-mcp\\browser-smoke.js")
```

也可以在 `code` 参数里直接 `require`/读文件，但**用 `filename` 更省事**：不用把代码再拼一遍。

返回形如：

```json
{
  "summary": "全部通过 (8 项)",
  "lines": ["PASS  页面 #/  [0行/12卡片]", "..."],
  "failures": [],
  "bundle": "index-BPwPplKN.js"
}
```

失败时 `failures` 会给出具体断言和观测值，**不需要再补跑一轮去定位**。

## 检查项

| 检查 | 不变式 |
|---|---|
| 页面 `#/` 执行中心 | `h2=执行中心`，脚本卡片 ≥1，**不得出现「暂无可执行脚本」** |
| 页面 `#/scripts` | `h2=脚本管理`，表格行 ≥8 |
| 页面 `#/tenants` | `h2=租户管理`，表格行 ≥1 |
| 页面 `#/scenarios` | `h2=场景编排`，表格行 ≥1 |
| 页面 `#/history` | `h2=执行历史`，表格行 ≥50 |
| 页面 `#/settings` | `h2=设置`，表格行 ≥7 |
| 全部页面 | 无未在白名单内的 console error / pageerror |
| 全局查找 ⌘K | 真实按键能打开 `.sb-search-dialog`；搜「成功」命中「成功示例」**且**过滤掉「睡眠30秒」；Esc 关闭 |
| 真实执行闭环 | 默认跳过（`RUN_EXEC=false`）；开启后要求执行历史 +1 且最新一条为成功 |

数据量断言（8/1/55/7 行）是**下限**，数据增长不会导致失败；只有页面渲染挂掉才会。

## 为什么要这么写（都是踩过的坑）

| 坑 | 正确做法 |
|---|---|
| `offsetParent === null` 用来判可见性 | **fixed 浮层为 null，会误判不可见**。用 `getBoundingClientRect().width/height > 0` |
| 用「搜索脚本」找全局查找面板的输入框 | 执行中心**自带**搜索框，placeholder 也含「搜索脚本」。面板要认「快捷操作」 |
| 用 `/成功/` 判断执行成功 | 脚本名就叫**「成功示例」**，必然误报。要么认状态字段，要么认历史行 +1 |
| `getByText('执行', {exact:true})` | 实际文案是「执行 →」，且**不是 `<button>`**，是带 `@click` 的 `<span class="sb-run-hint">`。要按文本 `.replace(/\s+/g,'') === '执行→'` 找任意标签 |
| `page.keyboard.press('Control+k')` 打不开面板 | **后台标签页收不到 CDP 真实键盘事件**（`Runtime.evaluate` 不受影响）。必须先 `page.bringToFront()` |
| 用 `m.text()` 过滤 favicon 500 | `text()` 只有 `Failed to load resource: ... 500 ()`，**URL 在 `m.location().url` 里**。要拼起来再匹配 |
| 用脚本名定位卡片 | 「成功示例」在「最近使用」紧凑行和「常用脚本」卡片里都出现，前者没有执行入口。要用独有描述「演示脚本成功执行」定位 |
| 判「面板打开」看 `document.body.innerText` | 元素可能正处于 `dialog-fade-enter` 过渡、宽高为 0。用 `waitForSelector(..., {state:'visible'})` |

## 怎么扩展

- **加页面**：往 `ROUTES` 加一条 `{ hash, h2, minRows, minCards?, notContain? }`。
- **改地址**：改 `BASE`。
- **开真实执行**：`RUN_EXEC = true`（会写数据，所以默认关）。
- 脚本会被原样回显，**保持精简**：注释和说明放这个 README，不要放进 `.js`。

## token 账（诚实版）

`filename` **不会**省掉回显 —— `browser_run_code_unsafe` 照样把整个文件内容回显一遍。省的是别的：

| | 改造前（本次会话实测过程） | 改造后 |
|---|---|---|
| 调用次数 | 十几轮（6 路由扫了 3 遍、⌘K 测了 4 次） | **1 次** |
| 每次附带 | 代码 + `Ran Playwright code` 回显 + `Open tabs`/`Page`/`Events` + 冗长 JSON | 脚本回显 + 约 250 字节结论 |
| 定位失败 | 常需再跑一轮取堆栈 | `failures` 直接给出断言与观测值 |
| 知识复用 | 每次重新猜选择器、重新踩坑 | 固化在上表 |

量级上大概从「几万 token 一次完整回归」降到「约 1~2k token」，**省的是往返次数和探错成本，不是回显**。脚本本身 5.6 KB，回显是主要固定开销，所以精简脚本是直接收益。
