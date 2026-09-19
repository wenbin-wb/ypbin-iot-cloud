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
