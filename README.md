# BigData Script Box

> 部署在 FusionInsight 大数据节点上的轻量级 Shell 脚本管理和执行工具。
> 个人/小团队使用，启动一次，新增脚本和参数都通过网页完成。

## 核心理念

* **程序只部署一次**：未来新增 Hudi / Flink / Hive / HBase / HDFS / YARN 脚本，**不需要修改 Java，不需要重新打包，不需要重新部署**。
* **动态参数自动生成表单**：每个脚本可声明 0~N 个参数，系统根据声明类型自动渲染执行页面。
* **统一参数规范**：脚本接收 `--key value` 长选项，Java 端使用 `ProcessBuilder(List<String>)` 调用，**禁止 `bash -c`** 拼接，避免 Shell 注入。
* **Mock 开发模式**：开发机无 Kerberos / Hadoop，所有功能以 mock 跑通；真实环境只需把 `scriptbox.mock` 改为 `false`。

## 技术栈

| 层 | 选型 |
| --- | --- |
| 语言 | Java 17 |
| 框架 | Spring Boot 3.3.5 (Spring MVC + Thymeleaf) |
| 持久化 | H2 File（`./data/db/scriptbox.mv.db`），MyBatis-Plus 3.5.7 |
| 前端 | Bootstrap 5（webjars）+ 原生 JavaScript，无前端构建工具 |
| 进程调用 | `java.lang.ProcessBuilder` |

明确**不**引入：Vue / React / Node / MySQL / Redis / Spring Cloud / Docker / MQ。

## 运行

### 前置条件

* JDK 17+
* Maven 3.8+
* Linux 权限：监听 80 端口需要 `CAP_NET_BIND_SERVICE`，一次性设置：

  ```bash
  sudo /sbin/setcap 'cap_net_bind_service=+ep' /usr/lib/jvm/jdk-17.0.15+6/bin/java
  ```

  之后用普通用户即可启动到 80 端口。

### 构建 & 启动

```bash
mvn clean package
java -jar target/script-box.jar
```

打开 http://localhost/

> 默认端口 `80`（详见 `application.yml`）。第一次启动会自动：
> 1. 在 `./data/` 下创建 `db/`、`scripts/`、`keytabs/`、`executions/` 目录。
> 2. 执行 `db/schema.sql` 建表（`CREATE TABLE IF NOT EXISTS`，幂等）。
> 3. 注入 1 个 mock 租户和 5 个示例脚本（success / failed / stderr-mix / timeout / large-output）。

### 测试

```bash
mvn test
```

测试覆盖：

| 类 | 覆盖点 |
| --- | --- |
| `TenantServiceTest` | 租户 CRUD / 启用切换 |
| `ScriptServiceTest` | 脚本 CRUD + 参数校验 + 脚本正文保存 |
| `ScriptExecutorTest` | success / failed / stderr / timeout / 大日志 |
| `H2PersistenceTest` | H2 文件写入验证 |
| `ApiSmokeTest` | REST API 端到端 |

## 目录结构

```
bigdata-script-box/
├── pom.xml
├── README.md
├── mock-scripts/                  # 内置示例脚本（启动时自动入库）
│   ├── success.sh
│   ├── failed.sh
│   ├── stderr.sh
│   ├── timeout.sh
│   └── large-output.sh
├── data/                          # 运行期数据（H2 文件、keytab、脚本正文、执行产物）
│   ├── db/scriptbox.mv.db
│   ├── scripts/{id}/script.sh
│   ├── keytabs/tenant_{id}_*.keytab
│   └── executions/{execId}/{stdout,stderr}.log
└── src/
    ├── main/
    │   ├── java/com/bigdata/scriptbox/
    │   │   ├── ScriptBoxApplication.java
    │   │   ├── config/             # DataInitializer / Properties / InMemoryMultipartFile
    │   │   ├── controller/         # PageController / Tenant / Script / Execution / History
    │   │   ├── dto/                # ApiResponse / ExecutionRequest
    │   │   ├── entity/             # Tenant / Script / ScriptParam / ExecutionHistory
    │   │   ├── executor/           # ScriptExecutor
    │   │   ├── mapper/             # MyBatis-Plus Mappers
    │   │   └── service/            # TenantService / ScriptService / ExecutionService / HistoryService
    │   └── resources/
    │       ├── application.yml
    │       ├── db/schema.sql
    │       ├── mappers/ScriptParamMapper.xml
    │       └── templates/          # Thymeleaf 模板
    └── test/...
```

## 页面

| 路径 | 功能 |
| --- | --- |
| `/` | 执行中心：按 category 分组展示脚本，点击进入动态表单 |
| `/scripts` | 脚本列表 + 新增入口 |
| `/scripts/edit?id=N` | 编辑脚本基本信息 + 在线编辑脚本正文 + 管理动态参数 |
| `/tenants` | 租户 CRUD + 上传 keytab + 测试租户（mock 或真实 kinit） |
| `/history` | 执行历史，点击查看 stdout / stderr / 参数 |

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

统一返回 `{code, message, data}`。

## 动态参数

支持的 `type`（见 `ScriptService.ALLOWED_TYPES`）：

* `text` / `textarea`
* `number`
* `select`（`options` 逗号分隔）
* `boolean`
* `date`

新增脚本 → 在 `/scripts/edit?id=N` 配置参数 → 保存。执行页面自动按 `sortOrder` 渲染。

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

部署到 FusionInsight 节点时改为 `mock: false`，上传真实 keytab，`ScriptExecutor` 会自动用 kinit 包装。

## 验收清单

1. `mvn test` 全部通过（12 个用例）✅
2. `mvn clean package` 成功 ✅
3. Spring Boot 正常启动 ✅
4. 浏览器访问 80 端口页面 ✅
5. 租户 CRUD ✅
6. 脚本 CRUD ✅
7. 参数可动态配置 ✅
8. 执行页面根据参数自动生成 ✅
9. success.sh 正常执行 ✅
10. failed.sh 正确识别失败 ✅
11. timeout.sh 正确超时 ✅
12. stdout / stderr 正常保存 ✅
13. execution_history 正常记录 ✅
14. H2 重启后数据存在 ✅（`./data/db/scriptbox.mv.db`）
15. README 完整 ✅