# BigData Script Box

> 部署在 FusionInsight 大数据节点上的轻量级 Shell 脚本管理和执行工具。
> 个人/小团队使用，启动一次，新增脚本和参数都通过网页完成。

## 文档

| 文档 | 内容 |
| --- | --- |
| `README.md`（本文） | 产品定位、技术栈、构建与运行、目录结构、页面与 REST 一览、验收清单 |
| `BACKEND.md` | 后端设计说明：分层与包结构、执行引擎、可靠性/清理、安全边界、配置项、已知问题 |
| `FRONTEND.md` | 前端设计说明：架构与约定（API 契约、动态参数模型、易踩坑清单）+ 优化记录与验证 |
| `SIMPLIFICATION.md` | 复杂度收敛方案：现状基线、优化清单、预期收益、如何降低复杂度（概念预算 / 单一入口 / 删除优先） |

## 核心理念

* **程序只部署一次**：未来新增 Hudi / Flink / Hive / HBase / HDFS / YARN 脚本，**不需要修改 Java、不需要重新打包、不需要重新部署**。
* **动态参数自动生成表单**：每个脚本可声明 0~N 个参数，系统根据声明类型自动渲染执行页面。
* **统一参数规范**：脚本接收 `--key value` 长选项，Java 端使用 `ProcessBuilder(List<String>)` 调用，**禁止 `bash -c`** 拼接，避免 Shell 注入。
* **Mock 开发模式**：开发机无 Kerberos / Hadoop，所有功能以 mock 跑通；真实环境只需把 `scriptbox.mock` 改为 `false`。

## 技术栈

| 层 | 选型 |
| --- | --- |
| 后端语言 | Java 17 |
| 后端框架 | Spring Boot 3.3.5 (Spring MVC + Validation) |
| 持久化 | H2 File（`./data/db/scriptbox.mv.db`），MyBatis-Plus 3.5.7 |
| 进程调用 | `java.lang.ProcessBuilder` |
| 前端 | **Vue 3 + Vite 5 + Element Plus 2** (SPA) |
| 前端状态 | Pinia |
| HTTP 客户端 | axios |

明确**不**引入：Thymeleaf / JSP / Bootstrap webjars / Vue 2 / React / Node 服务端渲染 / MySQL / Redis / Spring Cloud / Docker / MQ。

## 运行

### 前置条件

* JDK 17+
* Maven 3.8+
* Node 18+（仅构建期需要，运行时不需要 — 最终产物是单一 fat JAR）
* Linux 权限：监听 80 端口需要 `CAP_NET_BIND_SERVICE`，一次性设置：

  ```bash
  sudo /sbin/setcap 'cap_net_bind_service=+ep' /usr/lib/jvm/jdk-17.0.15+6/bin/java
  ```

  之后用普通用户即可启动到 80 端口。

### 构建 & 启动（生产模式，单 JAR 部署）

```bash
mvn clean package              # 自动跑 npm ci + vite build，把 dist 拷进 JAR
java -jar target/script-box.jar
```

打开 http://localhost/

> 默认端口 `80`（详见 `application.yml`）。第一次启动会自动：
> 1. 在 `./data/` 下创建 `db/`、`scripts/`、`keytabs/`、`executions/` 目录。
> 2. 执行 `db/schema.sql` 建表（`CREATE TABLE IF NOT EXISTS`，幂等）。
> 3. 注入 1 个 mock 租户和 5 个示例脚本（success / failed / stderr-mix / timeout / large-output）。
> 4. 服务 Vue SPA 静态资源（`BOOT-INF/classes/static/` 由 Maven 构建期从 `frontend/dist/` 注入）。

### 开发模式（前后端分离，热更新）

```bash
# Terminal 1: Spring Boot 在 :80
mvn spring-boot:run

# Terminal 2: Vite dev server 在 :5173，自动代理 /api/* 到 :80
cd frontend && npm install && npm run dev
```

打开 http://localhost:5173/ — 修改 Vue 组件即时热更新，REST 调用透明转发到后端。

### 测试

```bash
mvn test                      # 跑全部单元 + 集成测试
mvn test -DskipFrontend=true  # 跳过 npm 调用（离线环境）
```

28 个测试类 + 1 个基类 / 158 个用例（`src/test/java`），按主题大致分为：

| 主题 | 代表测试类 |
| --- | --- |
| 租户 / 脚本 CRUD | `TenantServiceTest`、`ScriptServiceTest`、`ScriptPackageServiceTest` |
| 执行引擎 | `ScriptExecutorTest`、`ProcessRunnerTest`、`ExecutionContextTest`、`ConcurrencyGateTest` |
| 可靠性与取消 | `ExecutionCancellationTest`、`ExecutionSnapshotTest`、`StoragePathServiceTest` |
| 参数与能力 | `ConditionalParamTest`、`DryRunTest`、`FileParameterTest`、`SyntaxCheckTest`、`PrecheckServiceTest`、`RiskLevelExecutionTest` |
| 产物 / 结果 / 脱敏 | `ArtifactServiceTest`、`ResultParserServiceTest`、`SensitiveDataMaskerTest` |
| 场景 / 批量 / 模板 / 版本 | `ScenarioServiceTest`、`BatchServiceTest`、`ScriptTemplateTest`、`ScriptVersionServiceTest`、`PresetServiceTest`、`GlobalVariableServiceTest` |
| 端到端 | `ApiSmokeTest`、`UxEndpointsTest`、`H2PersistenceTest`、`BaseIntegrationTest`（基类） |

> 完整的模块职责与测试覆盖矩阵见 `BACKEND.md`。

## 构建流程

```
┌──────────────┐  npm ci   ┌──────────────┐  vite build  ┌──────────────┐
│  frontend/   │ ────────▶ │ node_modules/│ ────────────▶ │  dist/       │
└──────────────┘           └──────────────┘              └──────────────┘
                                                               │
                          Maven exec plugin (generate-resources)
                                                               ▼
┌──────────────┐  copy-resources  ┌──────────────────────────────┐
│  src/main/   │ ────────────────▶ │ target/classes/static/       │
│  resources/  │                  │ (BOOT-INF/classes/static/)   │
└──────────────┘                  └──────────────────────────────┘
                                              │
                                  spring-boot:repackage
                                              ▼
                                    ┌──────────────────┐
                                    │ script-box.jar   │  ← 唯一交付物
                                    │  ~29 MB          │
                                    └──────────────────┘
```

构建期一次性把 Vue dist 拷进 Spring Boot 的 static 资源目录，最终只产出单一 fat JAR，**不需要 Nginx，不需要 Node，部署就是拷一个 jar 然后 `java -jar`**。

## 目录结构

```
bigdata-script-box/
├── pom.xml                              # Maven 配置（含 exec-maven-plugin 自动 npm 构建）
├── README.md                            # 本文：定位 / 构建运行 / 一览表
├── BACKEND.md                           # 后端设计说明（分层、执行引擎、可靠性、配置、已知问题）
├── FRONTEND.md                          # 前端设计说明（架构与约定 + 优化记录）
├── mock-scripts/                        # 内置示例脚本（启动时自动入库）
│   ├── success.sh
│   ├── failed.sh
│   ├── stderr.sh
│   ├── timeout.sh
│   └── large-output.sh
├── data/                                # 运行期数据（H2 文件、keytab、脚本正文、执行产物），gitignored
│   ├── db/scriptbox.mv.db
│   ├── scripts/{id}/script.sh
│   ├── keytabs/tenant_{id}_*.keytab
│   └── executions/{execId}/{stdout,stderr}.log
├── frontend/                            # Vue 3 SPA
│   ├── package.json
│   ├── package-lock.json
│   ├── vite.config.js                   # dev server 代理 /api → :80
│   ├── index.html                       # Vite SPA 入口
│   └── src/
│       ├── main.js                      # Vue 启动 + Element Plus / 图标注册
│       ├── App.vue                      # 根组件（包 AppLayout + <router-view/>）
│       ├── router.js                    # Vue Router（hash 模式，7 个路由）
│       ├── style.css                    # 全局样式 + 设计 token（中性色 + JetBrains Mono）
│       ├── api/                         # axios 封装（统一解包 {code,message,data}）+ 各模块端点
│       ├── components/                  # AppLayout / ParamForm / LogPane / ExecutionResultPanel /
│       │                                # ScriptCard / SBLabel / Cleanup*
│       ├── views/                       # Execute / Scripts / ScriptEdit / Tenants / Scenarios / History / Settings
│       └── utils/                       # format / labels / status / params / cleanup / clipboard / storage
└── src/                                 # Spring Boot 后端
    ├── main/
    │   ├── java/com/bigdata/scriptbox/
    │   │   ├── ScriptBoxApplication.java
    │   │   ├── config/                  # DataInitializer / Properties / InMemoryMultipartFile / WebConfig (SPA fallback)
    │   │   ├── controller/              # Tenant / Script / Execution / History (REST 控制器)
    │   │   ├── dto/                     # ApiResponse / ExecutionRequest
    │   │   ├── entity/                  # Tenant / Script / ScriptParam / ExecutionHistory
    │   │   ├── executor/                # ScriptExecutor
    │   │   ├── mapper/                  # MyBatis-Plus Mappers
    │   │   └── service/                 # TenantService / ScriptService / ExecutionService / HistoryService
    │   └── resources/
    │       ├── application.yml
    │       ├── db/schema.sql
    │       ├── mappers/ScriptParamMapper.xml
    │       └── static/                  # ← Vue dist 在构建期被复制到这里
    └── test/...
```

## 页面（7 个 SPA 路由）

| Hash 路径 | 功能 |
| --- | --- |
| `#/` | **执行中心**：按 category 分组卡片，点击打开 Drawer 渲染动态参数表单，执行后显示状态卡 + 结果/stdout/stderr/参数/产物 Tabs |
| `#/scripts` | **脚本管理**：Element Plus Table 列出所有脚本，新增 Drawer 上传 .sh，行内启停 / 收藏 / 复制 / 导入导出 / 编辑 / 删除；**点击「执行」在当前页直接打开执行 Drawer**（与执行中心共用 `ScriptExecutionDrawer.vue`），执行完原地展示结果与日志，不跳页 |
| `#/scripts/edit?id=N` | **脚本编辑**：基本信息 + 脚本正文 + 动态参数（增删、类型、必填、默认值、选项、显示条件），含语法检查、执行前检查、参数方案、版本回滚 |
| `#/tenants` | **租户管理**：Element Plus Table，新增/编辑 Drawer，keytab 上传，测试租户（Mock 模式返回模拟 kinit 输出） |
| `#/scenarios` | **场景**：多脚本顺序编排（每步可选参数方案 / 失败继续），一键按租户执行并查看分步结果 |
| `#/history` | **执行历史**：筛选（状态/脚本/租户/关键字/日期）+ 详情抽屉（参数 / stdout / stderr / 结果 / 产物 / 快照，可按快照重跑） |
| `#/settings` | **设置**：全局变量管理与清理面板（预览 → 输入 CLEAN 确认 → 报告 / 历史） |

> Vue Router 使用 hash 模式 (`createWebHashHistory`)，这样 SPA 路由完全在浏览器端处理，Spring Boot 只需服务 `index.html` 和静态资源，无需任何 rewrite 规则。

## REST API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET / POST / PUT / DELETE | `/api/tenants` `/api/tenants/{id}` | 租户 CRUD |
| POST | `/api/tenants/{id}/enabled?enabled=true/false` | 启用切换 |
| POST (multipart) | `/api/tenants/{id}/keytab` | 上传 keytab（不入库） |
| POST | `/api/tenants/{id}/test` | 测试租户（mock 模式返回假数据） |
| GET / POST (multipart) / PUT (multipart) / DELETE | `/api/scripts` `/api/scripts/{id}` | 脚本 CRUD（含上传 .sh） |
| GET / PUT | `/api/scripts/{id}/body` | 读取/保存脚本正文 |
| POST | `/api/scripts/{id}/enabled?enabled=true/false` | 启用切换 |
| GET / PUT | `/api/scripts/{id}/params` | 参数列表 / 替换（PUT 整组覆盖） |
| POST | `/api/executions` | 执行（body: `{scriptId, tenantId, params:{}}`） |
| GET (text/plain) | `/api/executions/{id}/stdout` | 读取 stdout |
| GET (text/plain) | `/api/executions/{id}/stderr` | 读取 stderr |
| GET | `/api/history?limit=200` | 最近执行 |
| GET | `/api/history/{id}` | 单条详情 |

统一返回 `{code, message, data}`（前端在 `api/http.js` 只解包一次，见 `FRONTEND.md`）。

其余端点分组（完整参数与语义见 `BACKEND.md`）：

| 分组 | 路径前缀 |
| --- | --- |
| 执行控制 | `/api/executions/{id}/cancel`、`/state`、`/rerun`、`/result`、`/artifacts[/{name}]`、`GET /api/executions/active`、`POST /api/executions/preview`（dry run） |
| 批量执行 | `POST /api/batches/execute`、`GET /api/batches/{batchId}` |
| 参数方案 / 版本 | `/api/scripts/{id}/presets[/{presetId}]`（含 `/summary`、`/{presetId}/apply`）、`/api/scripts/{id}/versions[/{versionNo}[/rollback]]` |
| 前置检查 / 语法检查 | `POST|GET|PUT /api/scripts/{id}/precheck`、`POST /api/scripts/syntax-check` |
| 打包导入导出 | `GET /api/scripts/{id}/export`、`POST /api/scripts/import` |
| 收藏 / 复制 / 引用计数 | `POST /api/scripts/{id}/favorite`、`/copy`、`GET /api/scripts/{id}/related-counts` |
| 脚本模板 | `GET /api/script-templates[/{code}]`、`POST /api/scripts/from-template` |
| 文件上传 | `POST /api/uploads`（作为 file 类型参数注入本次执行） |
| 场景编排 | `/api/scenarios[/{id}]`、`PUT /api/scenarios/{id}/steps`、`POST /api/scenarios/{id}/run`、`GET /api/scenarios/{id}/related-counts` |
| 全局变量 | `/api/global-variables[/{id}]` |
| 清理与设置 | `POST /api/admin/cleanup/preview`、`/execute`、`GET /api/admin/cleanup/history`、`GET|PUT /api/admin/settings` |
| 系统 | `GET /api/system/info` |

## 动态参数

支持的 `type`（见 `ScriptService.ALLOWED_TYPES`）：

| type | 控件 | 说明 |
| --- | --- | --- |
| `text` | `<el-input>` | 单行文本 |
| `textarea` | `<el-input type="textarea">` | 多行文本 |
| `number` | `<el-input-number>` | 数字 |
| `select` | `<el-select>` | 枚举，`options` 逗号分隔 |
| `boolean` | `<el-switch>` | 开关 |
| `date` | `<el-date-picker>` | 日期选择器 |

新增脚本 → 在「脚本管理 → 编辑」配置参数 → 保存。执行页面自动按 `sortOrder` 渲染。

## 执行模型

1. 校验租户和脚本的 `enabled`。
2. 用声明的参数对输入做必填/类型/枚举校验，应用默认值。
3. 创建 `data/executions/{execId}/`。
4. 构造 `bash <scriptPath> --k1 v1 --k2 v2 …`。
5. **Mock 模式**：直接跑。**真实模式**：若租户有 keytab，用 `_kinit_wrap.sh` 包装，先 `kinit -kt keytab principal` 再 `exec` 脚本。
6. 后台线程并发 drain stdout / stderr，避免管道缓冲阻塞。
7. `process.waitFor(timeout, SECONDS)`，超时则 `destroyForcibly()`。
8. 落 `execution_history` 表。

### 安全

* **不允许任意脚本路径**：执行前校验 `scriptPath` 必须以 `scriptbox.scripts-dir` 开头。
* **不允许 `bash -c`**：命令通过 `ProcessBuilder(List<String>)` 传参，不进入 shell 解析。
* **不允许任意扩展名**：只接受 `.sh` 脚本、`.keytab` keytab。
* **大小限制**：脚本 1MB，日志单次返回 1MB。
* **路径遍历防御**：`persistScriptBody` 写文件后再次校验前缀。
* **敏感 Key 不打印**：日志中只打印脚本路径和参数键名，不打印参数值。

## Mock 与真实环境切换

`application.yml`：

```yaml
scriptbox:
  mock: true        # true = 跳过 kinit，test 端点返回模拟 klist
  data-dir: ./data
  scripts-dir: ./data/scripts
  keytabs-dir: ./data/keytabs
  executions-dir: ./data/executions
  max-log-bytes: 1048576
  max-script-bytes: 1048576
```

> 上面只是最常用的一部分；`shell-executable`、`max-concurrent`、`max-batch-rows`、`retention-*-days`、
> 产物限额（`max-artifact-bytes` / `max-artifact-files`）等全部配置项与默认值见 `BACKEND.md`。

部署到 FusionInsight 节点时改为 `mock: false`，上传真实 keytab，`ScriptExecutor` 会自动用 kinit 包装。

## 验收清单

1. `mvn test` 全部通过（28 个测试类 + 1 个基类 / 158 个用例）✅
2. `mvn clean package` 成功（自动跑 npm 构建，最终单 JAR ~29 MB）✅
3. Spring Boot 正常启动 ✅
4. 浏览器访问首页（SPA shell 由 Spring Boot 提供）✅
5. 7 个页面（执行中心 / 脚本 / 脚本编辑 / 租户 / 场景 / 历史 / 设置）渲染正常 ✅
6. 执行中心按 category 分类卡片，点击 Drawer 打开动态表单 ✅
7. 5 个 mock 脚本端到端执行（success / failed / timeout / stderr-mix / large-output）✅
8. stdout / stderr 正常保存并展示（真实为空显示「无 stdout 输出 / 无 stderr 输出」，读取失败显示「读取 xx 失败」+ 重试）✅
9. execution_history 正常记录 ✅
10. H2 重启后数据存在 ✅（`./data/db/scriptbox.mv.db`）
11. 文档完整：`README.md`（本文）+ `BACKEND.md` + `FRONTEND.md` ✅