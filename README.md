# ypbin-iot-cloud

> IoT 平台（三个部署单元：gateway / business / access）。**当前是 M0a 骨架**：
> 可编译、第一批门禁可跑，**不含任何业务逻辑**。

设计事实源在母仓（本仓不含设计文档）：

| 文档 | 位置 | 用途 |
|---|---|---|
| `IOT-CLOUD-SPEC.md` | `../IOT-CLOUD-SPEC.md` | 本项目的详细设计 spec（§2 模块树、§9 门禁、§10 里程碑） |
| `IOT-M0A-IMPLEMENTATION-PLAN.md` | `../IOT-M0A-IMPLEMENTATION-PLAN.md` | M0a 的分步实施计划（P0~P6） |
| 本仓代理记忆 | [`AGENTS.md`](AGENTS.md) | 包根、parent 版本、门禁命令、不发布模块纪律 |

## 技术基线

| 项 | 值 |
|---|---|
| 坐标 | `cn.ypbin:ypbin-iot-cloud-*`（BOM：`cn.ypbin:ypbin-iot-cloud-bom`） |
| parent | `cn.ypbin:ypbin-starter-dependencies:3.4.0`（**已发布正式版**，Central 可解析；`relativePath` 为空） |
| 版本 | `revision = 0.1.0-SNAPSHOT`（未发布） |
| Java | JDK 21 + Spring Boot 4.1（由 parent 管理） |
| **Java 包根** | **`cn.ypbin.iotcloud`** |
| JSON | 只允许 Jackson 3（`tools.jackson`） |

> ⚠️ **包根绝不能写成 `cn.ypbin.iot`**：那是 `ypbin-iot-starter` 的包根，而 `access` 模块
> （P4 起）会与 iot-starter 同处一个 classpath，包名冲突会直接炸装配。

## 模块树

```
ypbin-iot-cloud/
├── ypbin-iot-cloud-dependencies      [pom] 统一版本管理 + 本仓模块 parent（含覆盖率门禁配置）
├── ypbin-iot-cloud-bom              [pom] 对外 BOM
├── ypbin-iot-cloud-common            [库] 统一响应/异常/租户上下文/入站可信校验/工具（M0a 仅占位常量类）
├── ypbin-iot-cloud-api               [库] 服务间契约（Feign + DTO；含租约/归属契约，P1）
├── ypbin-iot-cloud-auth              [库] 认证/租户/用户/权限/字典/日志（M0b 从 admin 移植）
├── ypbin-iot-cloud-core              [库] 产品/物模型/设备/数据/规则/告警
├── ypbin-iot-cloud-openapi           [库] 开放 API（API Key + 限流）
├── ypbin-iot-cloud-gateway           [应用] 部署单元① 路由 + 鉴权 + 租户上下文注入（P2）
├── ypbin-iot-cloud-business          [应用] 部署单元② 装配 auth+core+openapi
├── ypbin-iot-cloud-access            [应用] 部署单元③ 设备接入（有状态；P0 不接 iot-starter，P4 接）
├── ypbin-iot-cloud-architecture-tests [不发布] ArchUnit + 源码规范 + 模块发布边界 + 规则有效性自检
├── ypbin-iot-cloud-integration-tests  [不发布] 集成测试（M0a 只有占位 IT，`-Pit` 触发）
├── tools/                            check-nullaway.sh · export-coverage.mjs · preflight.sh
├── .github/workflows/                ci.yml（构建与校验）· codeql.yml
└── docs/                             （spec §2 规划项，**尚未创建**；M0a 不产出文档目录，M0b 起放 ADR 与运行文档）
```

两个**不发布**模块（`architecture-tests` / `integration-tests`）**不在**顶层 `<modules>` 里，
而是由根 pom 的 `dev-only` profile（`activeByDefault`）承载 —— 这样 `-Prelease` 会让它们
自动离开发布反应堆。副作用：**任何显式 `-P` 都会让 `dev-only` 失效**，架构门禁随之停跑
（所以跑门禁只能是 `mvn -B -ntp clean verify`，见下文）。

## 门禁清单（IOT-CLOUD-SPEC.md §9.1 的 13 项）

| # | 门禁 | 命令 | M0a 状态 |
|---|---|---|---|
| 1 | 架构约束（ArchUnit + **规则有效性自检**） | `mvn clean verify` | ✅ 已接入 |
| 2 | 源码规范（禁内联 FQCN / `@Bean` 覆盖 / autoconfig 注册 / 禁 ordinal） | 同上 | ✅ 已接入 |
| 3 | 模块发布边界 | 同上 | ✅ 已接入 |
| 4 | 配置元数据 | 同上 | ⏳ M0b |
| 5 | 空值语义（NullAway + 执行自检） | `tools/check-nullaway.sh` | ⏳ M0b（脚本已就位，未接 CI） |
| 6 | 依赖版本收敛 | `mvn -Pdep-convergence validate` | ⏳ M0b |
| 7 | 配置元数据漂移 | `node tools/export-config-metadata.mjs --check` | ⏳ M0b（脚本待移植） |
| 8 | 覆盖率快照（仅模块集合） | `node tools/export-coverage.mjs --check` | ⏳ M0b（脚本已就位，快照未生成） |
| 9 | 覆盖率门禁 | `mvn clean verify` | ✅ 已接入（**M0a 阈值：指令 ≥ 0.30**） |
| 10 | 集成测试 | `mvn -Pit verify` | ⏳ M0a 只有占位 IT，P5 才有真实场景 |
| 11 | 供应链 SBOM | `mvn -Psbom verify -DskipTests` | ⏳ M0b |
| 12 | 代码风格 | `mvn spotless:check`（已绑 `process-test-classes`，`verify` 会带上） | ✅ 已接入 |
| 13 | 发布前总检 | `tools/preflight.sh` | ✅ 已就位（只跑 M0a 已接线的门禁） |

> **阈值为什么是 0.30 而不是 spec 的 0.80**：骨架几乎是空的，照 0.80 必然全红。
> **M0b 起必须提到指令 ≥ 0.80 / 分支 ≥ 0.64**，届时同步改
> `ypbin-iot-cloud-dependencies/pom.xml` 的 `jacoco-check` 与 `tools/export-coverage.mjs` 的 `GATE`。
>
> **第 9 项在 M0a 的实际覆盖面（实测，2026-09-18）**：`mvn -B -ntp -fae clean test` 下只有
> `ypbin-iot-cloud-common` 真的被度量并通过（指令 13/13，`All coverage checks have been met.`）；
> 其余模块 JaCoCo 以「missing execution data file」（没有测试）或「missing classes directory」
> （`architecture-tests` 没有主源码）**跳过** —— 也就是「有主源码但没测试」的模块会**静默绕过**
> 覆盖率门禁。这是已知限制，M0b 的覆盖率快照门禁（第 8 项）负责把它暴露出来。

## 本地命令

```bash
export JAVA_HOME=/usr/lib/jvm/java-1.21.0-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
export MAVEN_OPTS=-Xmx768m

# 编译（跨模块一律带 -fae，避免「没报错」其实只是「没跑到」）
mvn -B -ntp -fae -DskipTests test-compile

# 全量单元测试 + 全部门禁（架构 / 源码规范 / 模块边界 / spotless / 覆盖率）
mvn -B -ntp -fae clean verify

# 只校验代码风格（不通过就先 spotless:apply）
mvn -B -ntp spotless:check
mvn -B -ntp spotless:apply

# 集成测试（只跑 *IT.java；it profile 已显式跳过 surefire）
mvn -B -ntp -Pit verify

# 一次性总检
tools/preflight.sh
```

**本机（2 vCPU / 5G）约定**：同一时刻只跑一个 Maven；不在本机跑容器集成测试与前端构建
（容器 IT 交 CI 或另一台机器，见 spec §4.5）。

## 本地开发形态（M0a 只有端口占位）

M0a 只保证三单元「能起」：`gateway` 18080（WebFlux）、`business` 18081、`access` 18082，
健康检查 `curl -fsS localhost:<port>/actuator/health`。路由、鉴权、租户上下文注入、入站可信校验
分别在 P2/P3/P4 落地；本地五进程形态（含 MySQL / Redis，关 Nacos）见 spec §4.5。

启动方式与**当前产物形态**（实测，2026-09-18）：

```bash
mvn -B -ntp -pl ypbin-iot-cloud-gateway -am spring-boot:run    # 本地启动（推荐）
```

- 三个应用模块都声明了 `spring-boot-maven-plugin`，所以 `mvn spring-boot:run` 可用；
- **但 `mvn package` 目前只产出瘦 jar**：pom 里只声明了插件、没有声明 `repackage` 执行
  （父 pom 链里没有 Boot 的 pluginManagement，声明插件不会自动绑定 repackage）。
  P5/P2 需要可执行 jar 时再显式添加，并注意：`architecture-tests` / `integration-tests`
  以 test 作用域依赖这三个模块，**直接把 fat jar 当成主产物有风险**（fat jar 的类在
  `BOOT-INF/classes/` 下，下游同一反应堆内解析到的可能是它）——
  更稳的做法是 `repackage` 配 `<classifier>exec</classifier>`，保留瘦 jar 作为主产物。
  （该风险尚未实测，按 Maven reactor 解析语义推断，标注为**未核实**。）

## 许可

Apache License 2.0（见 [`LICENSE`](LICENSE)）。
