# ypbin-iot-cloud · 代理记忆与开发规范（AGENTS）

> 本文件是本仓的自动化记忆入口（AGENTS.md / CLAUDE.md 双读），**任何在此仓库的工作会话开工前必须读取**。
> 本仓是**独立 git 仓**（与母仓 `ypbin-starter`、`ypbin-admin`、`ypbin-admin-ui`、`ypbin-site`、
> `ypbin-iot-starter` 并列），但**不是独立规范体系**：全局 `~/.dsh/AGENTS.md` 的铁律 R1–R8
> 与母仓 `ypbin-starter` 的编码铁律同样适用于本仓。

---

## 0. 事实源（先读，别靠记忆）

| 主题 | 位置 |
|---|---|
| 本项目的详细设计 spec（模块树 §2 / 门禁 §9 / 里程碑 §10 / 关键机制 §12） | 母仓 `../IOT-CLOUD-SPEC.md` |
| M0a 分步实施计划（P0~P6、验收命令、风险） | 母仓 `../IOT-M0A-IMPLEMENTATION-PLAN.md` |
| IoT 接入框架（access 的上游依赖） | `../ypbin-iot-starter/AGENTS.md` |
| 门禁脚手架与 pom 写法的样板 | `../ypbin-iot-starter`（**只读参考**，勿改） |
| 母仓编码铁律与教训索引 | `../ypbin-starter/AGENTS.md`、`../MEMORY-ROUNDS.md` |

---

## 1. 不可违背的坐标纪律

| 项 | 值 | 理由 |
|---|---|---|
| 仓库 / 坐标 | `ypbin-iot-cloud` / `cn.ypbin:ypbin-iot-cloud-*` | spec §2 |
| **Java 包根** | **`cn.ypbin.iotcloud`** | `access` 会与 `ypbin-iot-starter`（包根 `cn.ypbin.iot`）**同处一个 classpath**，包根撞车会炸装配。**绝不允许**写成 `cn.ypbin.iot` |
| 各模块子包 | `cn.ypbin.iotcloud.{common,api,auth,core,openapi,gateway,business,access}` | spec §2 |
| parent | `cn.ypbin:ypbin-starter-dependencies:**3.4.0**`（已发布正式版，Central 可解析） | 干净环境可构建；NullAway / dep-convergence 等门禁 profile 定义在该父 pom 里（BOM import 拿不到）。**`relativePath` 保持为空**，版本显式锁定、不自动跟随 |
| `revision` | `0.1.0-SNAPSHOT`（未发布） | 与 `ypbin-iot-starter` 一致 |
| Java / Boot | JDK 21 + Spring Boot 4.1（父 pom 管理） | 与母仓一致 |
| JSON | 只允许 **Jackson 3**（`tools.jackson`） | 母仓已全面切换 |
| 设备接入依赖 | `cn.ypbin:ypbin-iot-bom` | **尚未发布**（Central 404）→ `ypbin-iot-cloud-dependencies` 里只保留注释占位；access 在 **P4** 才接 |

---

## 2. 不发布模块纪律（母仓教训二）

`ypbin-iot-cloud-architecture-tests` 与 `ypbin-iot-cloud-integration-tests` **不发布**，必须：

1. 写进根 pom 的 `dev-only` profile（`activeByDefault`），**不得**写进顶层 `<modules>`；
   - 理由：`maven.deploy.skip=true` 对 `central-publishing-maven-plugin`（`extensions=true`）**无效**，
     未签名产物混进上传包会让整个 deployment 校验 FAILED（母仓 3.0.0 首发即如此）；
2. 模块 pom 里声明 `maven.deploy.skip=true` + `gpg.skip=true`（ModulePublishingTest 会校验）；
3. 被 `ModulePublishingTest`（MTP-01/02/03）强制约束；
4. **新增 profile 时必须显式带回这两个模块**（如 M0b 的 `sbom`）：显式 `-P` 会让 `dev-only` 失效。

> ⚠️ 推论：**跑门禁只能用 `mvn -B -ntp clean verify`（不带 `-P`）**；本地把多个 `-P` 组合起来跑，
> 架构门禁会静默停跑。发布前用 `tools/preflight.sh`。

---

## 3. 门禁命令（M0a 第一批）

```bash
export JAVA_HOME=/usr/lib/jvm/java-1.21.0-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
export MAVEN_OPTS=-Xmx768m

mvn -B -ntp -fae -DskipTests test-compile   # 编译（跨模块一律 -fae）
mvn -B -ntp -fae clean verify               # 唯一会跑全部第一批门禁的调用方式
mvn -B -ntp spotless:check                  # 代码风格（不通过先 spotless:apply）
mvn -B -ntp -Pit verify                     # 集成测试（M0a 只有占位 IT）
tools/preflight.sh                          # 一次性总检
```

**已接入（第一批）**：① ArchUnit（含规则有效性自检） ② 源码规范 ③ 模块发布边界 ④ spotless
⑤ JaCoCo 覆盖率门禁 ⑥ CI `mvn -B -ntp clean verify`。

> 覆盖率阈值**保持 spec §9.1 第 9 项原值：指令 ≥ 0.80 / 分支 ≥ 0.64**（不放宽阈值）。
> 曾一度想为「骨架期」把它降到 0.30，实测没必要：真正被度量的模块（有测试的）覆盖率高，
> 没测试的模块会被 jacoco 静默跳过（见第 4 节第 1 条），所以 0.80 在 M0a 就能达成。

**M0b 才接（现在只留脚本与登记，不要提前接）**：NullAway（`tools/check-nullaway.sh`）、
依赖版本收敛（`-Pdep-convergence`）、配置元数据漂移、覆盖率快照（`tools/export-coverage.mjs`）、
SBOM（`-Psbom`）、preflight 全量。依据 spec §9.1 与 §10 的 M0b「13 项门禁全绿」出口条件。

---

## 4. 已知限制（写在明处，别当已解决）

1. **覆盖率门禁会「空转」**：实测（2026-09-18，`mvn -B -ntp -fae clean test`）只有
   `ypbin-iot-cloud-common` 与 `ypbin-iot-cloud-api` 真的被度量（P1 落地后两者都已达 0.80/0.64，
   `All coverage checks have been met.`）；其余模块都跳过，原因两类：
   - 「missing execution data file」= 模块没有测试（`auth`/`core`/`openapi`/三个部署单元）；
   - 「missing classes directory」= 模块没有主源码（`architecture-tests` 只有测试源码）。
   即「有主源码但没有测试」的模块会**静默绕过**覆盖率门禁。M0b 的覆盖率快照门禁负责暴露它（spec §9.1 第 8 项）。
2. **启动类不计覆盖率**：`**/*Application.class` 在 jacoco 配置里被显式排除
   （M0a 不写 `@SpringBootTest`，三单元「能起」属 P5 的运行期验收）。这是**显式豁免**，不是放宽阈值。
3. **`tools/generated/cloud-coverage.json` 尚未生成**：M0a 骨架没有可度量的真实代码（见第 1 条），
   空快照会把文档里的覆盖率变成永远为零的假数据。M0b 首次生成。
4. **`access` 还没有接 iot-starter**（P4），M0a 只验证第三单元能被单独启动与探活。
5. **端口 18080/18081/18082 是 M0a 占位值**，P2/P5 与本地五进程形态对齐时复核。
6. **三个部署单元的 `mvn package` 目前只产出瘦 jar**（实测 2026-09-18：`mvn -B -ntp clean verify`
   里没有任何 `repackage` 输出）。原因：pom 只声明了 `spring-boot-maven-plugin`，而父 pom 链里
   **没有** Boot 的 pluginManagement，声明插件不会自动绑定 `repackage`（BOM import 不传递 pluginManagement）。
   影响：`mvn spring-boot:run` 可用，`java -jar` 不可用。P5/P2 加可执行 jar 时注意
   「fat jar 作为主产物 + `architecture-tests`/`integration-tests` 以 test 依赖这三个模块」的组合风险
   （fat jar 的类在 `BOOT-INF/classes/` 下）——**该风险未实测**，安全做法是 `repackage` 配
   `<classifier>exec</classifier>`。

---

## 5. 编码铁律（继承母仓，本仓同步适用）

- 类级 Javadoc 必须带 `@author wenbin` + `@since <日期>`；**禁 `@date`、禁版本号**；顶部 Apache-2.0 license 头。
- **禁内联全限定类名**（一律顶部 import）；**禁 `ordinal()`**（枚举显式 `code` + `desc`）。
- 返回 List/Set/Map 查无数据**返回空集合**；字面量集合统一 `List.of` / `Map.of` / `Set.of`，
  **禁用** `Collections.emptyXxx` / `singletonXxx`（架构测试会拦）。
- **禁静默降级/吞异常**：日志必须传完整堆栈（`log.error("...", ex)`），禁 `printStackTrace()` / `System.out`。
- `@Transactional` 必须 `rollbackFor = Exception.class`；禁循环内 DB/RPC；批量 `IN` 前判空短路。
- 远程调用必须显式 `connectTimeout` / `readTimeout`；时间统一 `LocalDateTime`（GMT+8）。
- 每个 `@Bean` 带 `@ConditionalOnMissingBean`；`@AutoConfiguration` 必须登记到
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。
- 提交前 `spotless:apply` 再 `test`/`compile`；**验证后才宣称完成**。

---

## 6. 本机（低配）约定

2 vCPU / 5G：**同一时刻只跑一个 Maven**；跨模块命令带 `-fae`；
**不在本机跑 Testcontainers/容器集成测试与前端构建**（交 CI 或另一台机器，spec §4.5）。
