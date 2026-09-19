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
package cn.ypbin.iotcloud.access.lease;

import cn.ypbin.iotcloud.api.lease.LeaseEpochRules;
import java.time.LocalDateTime;

/**
 * 本地租约快照。
 *
 * <p>采集热路径（设备轮询、点位映射）只读这份内存快照，<b>不查库、不调 RPC</b>
 * （spec §3.1① 的 I5 约束）。它由续约响应与领取响应驱动刷新。</p>
 *
 * @param leaseExpireAt 该租户租约的到期时间
 * @param epoch         该租户的台账版本号（快照准入与事件应用都靠它）
 * @author wenbin
 * @since 2026-09-19
 */
public record LeaseSnapshot(LocalDateTime leaseExpireAt, long epoch) {

    /**
     * 本地判定是否已过期。
     *
     * @param now 当前时刻
     * @return 已过期返回 {@code true}
     */
    boolean expiredAt(LocalDateTime now) {
        return LeaseEpochRules.isLeaseExpired(leaseExpireAt, now);
    }
}
