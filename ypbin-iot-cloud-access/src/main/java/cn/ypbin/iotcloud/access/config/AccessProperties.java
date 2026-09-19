/*
 * Copyright (c) 2024-present ypbin-iot-cloud authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.ypbin.iotcloud.access.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * access（部署单元③）自身参数。
 *
 * <p>与 business 的 {@code ypbin.lease.*} 分开：那边是<b>服务端</b>的租约有效期与扫描周期，
 * 这里是<b>客户端</b>的节点身份与续约节奏。两者只有一个硬关系——{@link #renewIntervalMs}
 * 必须明显大于「一次续约的最坏耗时」（见 {@code AccessStartupValidator}）。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Getter
@Setter
@ConfigurationProperties(prefix = AccessProperties.PREFIX)
public class AccessProperties {

    /** 配置前缀。 */
    public static final String PREFIX = "ypbin.access";

    /**
     * 节点标识：租约归属的键。
     *
     * <p><b>必须非空</b>——为空会让所有副本注册成同一个节点，租约归属直接失效
     * （{@code AccessStartupValidator} 在启动期就拒绝）。默认值来自 {@code application.yml} 的
     * {@code ${HOSTNAME:access-local}}（不是本类的字段默认值）；本地多开需显式区分。</p>
     */
    private String nodeId = "";

    /**
     * 本节点最多带多少租户；<b>为空表示不限</b>（自用单节点全量模式，spec §3.1①）。
     */
    private Integer capacity;

    /**
     * 是否在启动期执行握手（注册 + 领取）。
     *
     * <p>默认开启——**生产必须开启**：不握手就没有租约，节点不会采集任何租户（安全但无用）。
     * 提供开关是为了让「只验装配」的上下文测试不必真的连 business；
     * 关掉时节点处于「已启动但零采集」状态，属安全失败方向。握手本身的 fail-fast 语义不变
     * （见 {@code AccessStartupRunner}）。</p>
     */
    private boolean startupHandshakeEnabled = true;

    /**
     * 本节点要接入的设备清单（M0a 用配置给出；M0b 换成从台账表读取）。
     *
     * <p>租约只回答「本节点该采哪些<b>租户</b>」，设备是租户的下级——M0a 还没有设备表（§12.2 的迁移在 M0b），
     * 所以这里用配置把「租户 → 设备 → 连接」三件事讲清楚；`TenantLinkManager` 再按租约归属决定
     * 绑哪些设备、断哪些设备。</p>
     */
    private List<DeviceEntry> devices = new ArrayList<>();

    /**
     * 设备建链超时：显式配置（仓库铁律：远程调用禁止无超时默认客户端）。
     */
    private Duration deviceConnectTimeout = Duration.ofSeconds(5);

    /** 设备请求超时：显式配置。 */
    private Duration deviceRequestTimeout = Duration.ofSeconds(3);

    /**
     * 一台设备的接入定义（配置形态，M0b 由台账表替代）。
     *
     * <p>字段与字段名对齐 iot-starter 的契约（`DeviceSpec` / `ConnectionSpec`），不做改名映射。</p>
     */
    @Getter
    @Setter
    public static class DeviceEntry {

        /** 所属租户（决定它跟随哪份租约被绑定/断链）。 */
        private Long tenantId;

        /** 设备标识（全局唯一；断链与观测都用它）。 */
        private String deviceId;

        /**
         * 连接标识（同一租户内多台设备可共用一个连接；<b>跨租户共用会被拒绝</b>）。
         *
         * <p>为什么跨租户共用要拒绝：TCP 适配器的连接与设备会话是 1:1，撤销任一租约都会关闭那条共享 socket，
         * 另一个租户会被判「负责」却链路已死且不重连（复核 F1 实测）。要共用连接得先确认协议适配器支持
         * 一对多会话复用并把撤销语义一起设计好——不是配置层面顺手能开的口子。</p>
         */
        private String connectionId;

        /** 端点 URI，例如 {@code tcp://127.0.0.1:15002}（必须带 scheme，由 iot-starter 校验）。 */
        private String uri;

        /** 轮询间隔（毫秒）；0 表示不主动轮询（M0a 只验证建链/断链）。 */
        private long pollIntervalMs;
    }

    /**
     * 周期<b>重领</b>间隔（毫秒），默认 15s（= business 的失效扫描周期）。
     *
     * <p>为什么需要它：{@code acquire} 不只是「首次领取」——它是**接管的执行入口**（business 把
     * 待接管/已释放的租户分给调用的节点）。如果一个节点只在启动时领取一次，那么别的节点退出后留下的
     * 租户会停在「待接管」状态<b>永远没人接手</b>，§3.1① 的接管链路就断在最后一步。
     * 因此本节点定期重领：有富余容量时把孤儿租户接过来（契约保证 {@code acquire} 幂等、
     * 只续期不重复分配）。</p>
     *
     * <p>取值直接决定「节点退出 → 别的节点把它接过来」的最坏延迟：
     * {@code ttl + 扫描周期 + 重领间隔}（默认 30 + 15 + 15 = 60s）。复核建议不要大于扫描周期——
     * 重领是廉价且幂等的调用，没必要为省一次调用把接管延迟拉长。</p>
     */
    private long acquireIntervalMs = 15_000L;

    /**
     * 续约周期（毫秒），默认 10s（spec §3.1① 的默认值）。
     *
     * <p>必须大于「connect + read」的最坏耗时（默认 1s + 3s），否则一次卡顿就会让续约跨过
     * business 侧的租约到期时间，把自己卡成失效节点（启动期校验会拒绝这种配置）。</p>
     */
    private long renewIntervalMs = 10_000L;

}
