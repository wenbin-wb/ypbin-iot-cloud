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
package cn.ypbin.iotcloud.access.link;

import java.util.Set;

/**
 * 租户采集链路的<b>控制端口</b>（self-fencing 的执行面）。
 *
 * <p>为什么要有这层端口：租约维护（{@code AccessLeaseManager}）只负责判断「谁该采、谁必须停」，
 * 而真正「断链、停采、释放 socket」是协议栈的动作。M0a 还没有协议栈（{@code ypbin-iot-bom} 未发布），
 * 所以这里先定义一个<b>可替换的实现</b>，让 self-fencing 的语义现在就能被实现与测试——
 * 等 P4b 接上 iot-starter 时，换实现而不是改判定逻辑（spec §3.1① 的 self-fencing 是硬要求）。</p>
 *
 * <p>⚠️ 契约要求（spec §3.1①）：旧节点一旦发现自己租约失效，必须<b>自己</b>断链停采。
 * 不能指望「新节点去断旧节点的 socket」——对 Modbus/OPC UA 的 socket，新节点根本没有能力断别人的链路。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
public interface TenantLinkManager {

    /**
     * 开始采集某个租户（幂等）。
     *
     * @param tenantId 租户 ID
     */
    void startCollecting(Long tenantId);

    /**
     * 对单个租户执行 self-fencing：断链 + 停止采集（幂等）。
     *
     * @param tenantId 租户 ID
     * @param reason   停采原因（用于日志与排障）
     */
    void fence(Long tenantId, String reason);

    /**
     * 对整个节点执行 fencing：断开全部链路并停止采集（幂等）。
     *
     * @param reason 停采原因
     */
    void fenceAll(String reason);

    /**
     * 某租户当前是否在采集。
     *
     * <p>⚠️ <b>这不等价于「链路真的活着」</b>：设备探测失败、或该租户还没配设备时，本方法返回 true
     * 而实际链路数为 0。链路是否真的活着请看 {@code iotcloud.access.link.bound.devices}（绑定设备数 gauge）
     * 或 iot-starter 的会话数；把「负责」当「在采」会在设备侧故障时产生可观测失真（复核 F3）。</p>
     *
     * @param tenantId 租户 ID
     * @return 本节点负责该租户时返回 {@code true}（不代表链路已建立）
     */
    boolean isCollecting(Long tenantId);

    /**
     * 当前正在采集的租户集合（只读快照）。
     *
     * <p>与 {@link #isCollecting(Long)} 同一口径：包含「负责但零链路」的租户
     * （探测失败/未配设备）。真实链路数看 {@code iotcloud.access.link.bound.devices}。</p>
     *
     * @return 租户 ID 集合，永不为 {@code null}
     */
    Set<Long> collectingTenants();
}
