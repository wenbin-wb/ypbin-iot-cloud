# 租约与归属契约（access ↔ business）· M0a 定稿

> 依据：`IOT-CLOUD-SPEC.md` §3.1（A9 细则）与 §3.1⑦（「M0a 必须定死的接口契约」）。
> 状态：**已落地实现**（`ypbin-iot-cloud-api` 的 `cn.ypbin.iotcloud.api.lease` 包）。
> 策略可延后，**契约不可延后**——改这个契约等于同时改 access 与 business 两侧。
> 编制：2026-09-18。

---

## 0. 一句话

access 通过 **6 个接口**从 business 拿「我该采哪些租户」并在运行期持续证明「我还活着」；
business 用 **租约 + 失效检测 + 两阶段接管**保证「同一时刻只有一个节点采某个租户」。

---

## 1. 六个接口（`ILeaseClient`，路径前缀 `/internal/lease`）

| # | 方法 | 语义 | 幂等性 | 失败/边界 |
|---|---|---|---|---|
| 1 | `register` | 节点启动注册（声明自己上线与容量上限） | **幂等**（重复注册=覆盖） | 注册失败 → 节点不得开始采集（**fail-fast**，不允许「没注册就自己采」） |
| 2 | `acquire` | 领取分给该节点的租户及其 `epoch` | 幂等（重复领取返回同一批） | 返回**空集合**表示暂无分给它的租户（不是错误）；节点保持空转并等下一轮 |
| 3 | `renew` | 周期性续约（默认 **10s**） | 幂等 | 响应给出**逐租户**新到期时间（`renewedLeases`）、被撤销租户（`revokedTenantIds`）与**节点级**信号（`nodeFenced`），共同构成 self-fencing 判据（见 §4） |
| 4 | `release` | 节点正常下线，归还租户 | 幂等（重复释放不报错） | 异常宕机不走此路径 → 由「到期 + 接管」兜底 |
| 5 | `queryAssignment` | 查某租户当前归属（节点/到期时间/epoch/状态） | 只读 | 无归属时返回 `null` 数据体（`R.data=null`），不是错误 |
| 6 | `batchEpoch` | **一次拉全量**租户的 `epoch`（周期对账用，默认 5 分钟） | 只读 | 响应带 `readAt`（读取完成时间，供实现侧做一致性校验与观测）；判据**只用 epoch**（见 §5） |

> 为什么「批量拉全量」而不是「每租户一次」：逐租户调用会在租户数增长时变成 N 次 RPC，
> 既慢又给 business 制造无谓压力（spec §3.1③ 明确要求批量）。

---

## 2. 状态机

```
        register+acquire
   ────────────────────────► ACTIVE ──────────release──────────► RELEASED
                              │  ▲  ▲                               ▲
              lease_expire_at │  │  │ ② 已过期但仍 ACTIVE：          │
              到期未被续约      ▼  │  │    可直接被新节点领走           │
                        PENDING_TAKEOVER ──────────────────────────┘
                                 │  │
                                 └──┴── ① 新节点接管（换节点 + epoch + 1）
```

- **`ACTIVE`**：节点正在采集，租约未到期。
- **`PENDING_TAKEOVER`**：`lease_expire_at < now`，business 扫描判定节点死亡后置入。
  **没有这个状态，"节点退出 → 待接管" 永远不会被触发，租户会静默离线**（spec §3.1① 点名的 v3 缺口）。
- **`RELEASED`**：正常下线归还。
- **两条真实存在的接管边（实现里都支持，缺一不可）**：
  1. `PENDING_TAKEOVER` → `ACTIVE`（新节点接管，**epoch + 1**）——失效扫描已经判定过；
  2. `ACTIVE`（已过期）→ `ACTIVE`（新节点接管，**epoch + 1**）——旧节点可能已死而扫描还没跑到；
     直接领走是安全的：旧节点即使还活着，它自己的租约也已过期，续约时会被明确告知撤销并 self-fencing。
- **`RELEASED` → `ACTIVE`**：正常释放后的再分配，**不递增 epoch**（台账没变）；⚠️ 这条对 §3.1② 的快照准入有影响，见 ADR-0001 §4。

---

## 3. epoch（台账版本号）语义

| 规则 | 内容 | 实现 |
|---|---|---|
| 快照准入 | 仅 `snapshot.epoch > local.epoch` 才可采用快照 | `LeaseEpochRules.shouldAdoptSnapshot` |
| 事件应用 | 仅 `event.epoch > local.epoch` 才应用；相等或更旧**丢弃** | `LeaseEpochRules.shouldApplyEvent` |
| 单调递增 | 台账变更与 `epoch + 1` **在同一事务**；到上限显式失败（不许回绕） | `LeaseEpochRules.nextEpoch` **只是纯函数**（算下一个值 + 上限校验）；「同事务」由调用方保证——M0a 无事务（见 ADR-0001 §2.1 的 M0b 必办） |
| 对账判据 | **只用 epoch**（设备数在「改参数」「删一台又加一台」时不变，会假阴性） | spec §3.1③ |

> **为什么必须同事务**：不同事务会造出「变更成功但 epoch 没涨」的状态，此时周期对账**永远看不出差异**——
> 这是对账机制最怕的失效模式（spec §3.1③）。

---

## 4. 失效检测与接管（fencing）

1. **失效检测**：business 定时扫描 `lease_expire_at < now` → 该租户置 `PENDING_TAKEOVER`。
2. **接管 = 两阶段**：
   - Ⅰ business **确认旧租约已过期**，撤销旧租约并写入新节点归属（同事务 + epoch 递增）；
   - Ⅱ 新节点**仅在确认旧租约过期后**才建链。
3. **旧节点 self-fencing（硬要求）**：旧节点一旦发现自己的租约不再有效，
   **必须立即断开本节点所有链路并停止采集**。
   - 判据必须用**组合判据** `LeaseEpochRules.needsSelfFence(state, leaseExpireAt, now)`
     （状态失效 **或** 已过期，二者取或）——只判状态会漏掉「ACTIVE 但已过期」这种长 GC 停顿/网络分区下的真实形态；
   - 到期时间由 `renew` 响应的**逐租户**回执（`renewedLeases[].leaseExpireAt`）刷新；
   - `revokedTenantIds`（逐租户被撤销）与 `nodeFenced`（节点级整体失效）同样触发 fencing；
   - **续约连续失败也必须触发 self-fencing**：节点无法证明自己还活着时不得继续采集（失败阈值由实现定，
     但「连续失败 → 停采」这条规则本身是契约的一部分，不能只在代码注释里）。
   - **不能只靠「新节点去断旧节点」**：Modbus/OPC UA 的 socket 在新节点手里没有任何办法关闭；
     而旧节点若只是长 GC 停顿或网络分区，进程还活着、socket 还开着 → **新旧同时轮询同一台 PLC**。

---

## 5. 周期对账（access 侧）

> ⚠️ **「拉全量台账」的接口不在本契约内**：`batchEpoch()` 只能**识别**不一致，**没有拉取台账内容的通路**。
> 台账（产品/设备/点位映射）属 M1 的数据模型，届时随台账模型一起定接口（见 §7）。
> 这条是独立复核（2026-09-18）明确指出的缺口，**不要当成已解决**。

- 默认 **5 分钟**一次：`batchEpoch()` 一次拉全量 → 与本地比对 → **只对不一致的租户**拉全量台账（**M1 落地**）；
- 对账/事件都失败 → **保留旧台账 + 在采集健康度上打「台账陈旧」标记**，不得静默继续；
- business 长时间不可用 → access **保持旧台账继续采集**（不退出）。

---

## 6. 调用侧硬约束（逐条标注落地状态）

| 约束 | 值/做法 | 出处 |
|---|---|---|
| 内部鉴权 | 请求头 `X-Internal-Token`；服务端 **fail-closed**、常量时间比较、失败转 **HTTP 200 + `R.code=401`** | `common` 的入站守卫（移植自 admin 已验证实现） |
| 超时 | connect **1s** / read **3s**（显式配置，禁止无超时默认客户端） | `LeaseFeignConfiguration` |
| 超时与续约周期的关系 | **read 必须远小于 10s 续约周期**，否则一次卡顿就会让续约跨过到期时间、把自己卡成失效节点 | 同上（有门禁断言） |
| 网关剥离 | 网关剥离名单**必须显式包含** `X-Gateway-Signed`（starter 默认名单不含它） | spec §4.4-2（P2 落实） |
| 响应体 | 一律 HTTP 200 + `R.code`；集合字段**永不为 null**（默认空集合，显式置 null 也被 getter 兜底为空集合，有测试锁定） | 本契约 DTO |
| 重试 | **显式 `Retryer.NEVER_RETRY`**：默认 `Retryer.Default` 是 5 次重试，单次续约最坏 ≈21.5s > 10s 周期，会把节点卡成失效；续约靠下一轮自然重发，需要重试的场景由上层做**有界**重试 | `LeaseFeignConfiguration` |
| 超时/重试的宿主覆盖 | 两个 Bean 都是 `@ConditionalOnMissingBean`（`SearchStrategy.ALL`，宿主在祖先链任意位置定义即可覆盖）：**覆盖即自负「不得让单次续约跨过周期」的责任** | `LeaseFeignConfiguration` + 独立复核 §4 |
| 启动期校验（服务端侧） | ✅ **P3 已落地**：business 启动时校验 `ypbin.lease.ttl > ypbin.lease.expected-renew-interval`（不满足记 ERROR），并在「未配内部凭证」「可分配租户为空」时各记一条 WARN —— 后者正是「租约链路在默认配置下空跑」这个坑 | `BusinessStartupChecker` |
| 启动期校验（**客户端侧**，P4 必办） | ⏳ 未落地：access 接上 Feign 后，必须校验「单次续约最坏耗时（connect+read×重试）< 续约周期」，覆盖 `LeaseFeignConfiguration` 的宿主同样要过这道校验 | 待 P4 |
| HTTP 200 信封的前提 | `BusinessException → HTTP 200 + R.code=401` 由 **`ypbin-starter-web` 的全局异常处理器**完成；`common` 只依赖 `starter-core` → **P3 起 business 必须显式引入 `ypbin-starter-web`**（版本已在 `-dependencies` 预管） | 独立复核 F8 |
| 时间假设 | 到期判断用 `LocalDateTime` 直接比较，**隐含「各部署单元同时区且 NTP 同步」**；跨时区部署需改 `Instant`（spec §6 允许协议时序用 `Instant`） | 独立复核 |

---

## 7. 明确「未定」的项（**不要当成遗漏**）

| 项 | 何时定 | 为什么现在不定 |
|---|---|---|
| **拉取台账内容/全量快照的接口**（§3.1② 的「启动拉全量」与 §5 的「只对不一致租户拉全量」） | **M1**（随台账数据模型） | P1 只定「归属与租约」；台账表在 M1 才存在，先定接口等于凭空发明字段 |
| 分配策略（哈希 / 注册表 / 静态） | M0 决策、**不晚于 M1** | M0a 只要求契约；单节点全量模式先跑通 |
| 凭据 `ref` 契约（不透明/本地解析/明文不下发/轮换） | **M0b 前** | 它是 `device` 表字段与 `-api` DTO，届时一并定 |
| EMQX provisioning 形态（内建 / 外部 HTTP / mTLS） | M1/M3（**已决策 M0a 不接 EMQX**） | 见 IOT-M0A-IMPLEMENTATION-PLAN.md §4 |
| 租约参数默认值（10s 续约 / 过期阈值 / 5min 对账）是否可配 | 实现时（P3/P4） | 契约里只固定「语义」，不固定「数值来源」 |

---

## 8. 契约变更纪律

1. 改本契约 = 同时改 **access 与 business**，且必须更新本文件与 `LeaseEpochRules` 的测试；
2. DTO **不暴露持久化实体**（沿用母仓规矩：实体→视图的投影放实现侧）；
3. 所有字段名与将来的表/实体**同名**（禁字段改名映射）；
4. 新增接口必须同时补：DTO 校验注解 + 契约测试 + 本文件表格。
