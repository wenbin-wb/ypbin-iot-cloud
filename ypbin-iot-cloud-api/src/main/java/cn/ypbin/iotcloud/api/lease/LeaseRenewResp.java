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

import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 续约响应：<b>本响应是 self-fencing 的判据来源</b>。
 *
 * <p>节点必须对 {@link #revokedTenantIds} 中的租户<b>立即断链并停止采集</b>：这些租户可能已被
 * 新节点接管，而此时旧节点若因长 GC 停顿/网络分区仍然活着，就会出现「新旧同时轮询同一台设备」
 * （IOT-CLOUD-SPEC.md §3.1① 的 fencing 要求）。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
@Getter
@Setter
public class LeaseRenewResp {

    /** 续约成功的租户 ID（空集合表示无，绝不为 null）。 */
    private List<Long> renewedTenantIds = List.of();

    /**
     * 已失效/已被接管的租户 ID —— 节点必须 self-fencing（断链 + 停采）。
     * 空集合表示无，绝不为 null。
     */
    private List<Long> revokedTenantIds = List.of();

    /** 下次续约的租约到期时间（节点须在此之前再次续约）。 */
    private LocalDateTime nextLeaseExpireAt;
}
