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
package cn.ypbin.iotcloud.api.lease;

import jakarta.validation.Valid;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 续约响应：<b>本响应是 self-fencing 的判据来源之一</b>。
 *
 * <p>节点必须做两件事：</p>
 * <ol>
 *   <li>对 {@link #revokedTenantIds} 中的租户<b>立即断链并停止采集</b>——它们可能已被新节点接管，
 *       而此时旧节点若因长 GC 停顿/网络分区仍然活着，就会出现「新旧同时轮询同一台设备」；</li>
 *   <li>若 {@link #nodeFenced} 为 {@code true}，说明本节点在 business 侧已不被认可（注册丢失/被判定死亡），
 *       必须<b>整体 fencing</b>：断开全部链路并停止采集，然后重新注册。</li>
 * </ol>
 *
 * <p>集合字段的 getter 做了空值兜底：即使对端显式送来 {@code null}，调用方也永远拿到空集合。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
@Getter
@Setter
public class LeaseRenewResp {

    /**
     * 续约成功的租户及其新到期时间（逐租户回执，见 {@link LeaseRenewAck}）。
     *
     * <p>{@code @Valid} 让 {@link LeaseRenewAck} 上的约束<b>可达</b>：business 若对自己的响应做校验，
     * 漏填 ack 字段会被立即发现，而不是让节点静默地不更新到期时间。</p>
     */
    @Valid
    private List<LeaseRenewAck> renewedLeases = List.of();

    /** 已失效/已被接管的租户 ID —— 节点必须对它们 self-fencing（断链 + 停采）。 */
    private List<Long> revokedTenantIds = List.of();

    /** 节点级 fencing 信号：{@code true} 表示本节点已不被认可，必须整体停采后重新注册。 */
    private boolean nodeFenced;

    /**
     * 空值兜底的续约回执。
     *
     * @return 回执列表，永不为 {@code null}
     */
    public List<LeaseRenewAck> getRenewedLeases() {
        return renewedLeases == null ? List.of() : renewedLeases;
    }

    /**
     * 空值兜底的被撤销租户列表。
     *
     * @return 租户 ID 列表，永不为 {@code null}
     */
    public List<Long> getRevokedTenantIds() {
        return revokedTenantIds == null ? List.of() : revokedTenantIds;
    }
}
