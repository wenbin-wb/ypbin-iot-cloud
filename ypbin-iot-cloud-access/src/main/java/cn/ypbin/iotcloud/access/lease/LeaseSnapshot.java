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
import cn.ypbin.iotcloud.api.lease.LeaseState;
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
     * 本地判定本租约是否必须 self-fencing。
     *
     * <p>直接<b>复用契约的组合判据</b> {@link LeaseEpochRules#needsSelfFence(LeaseState, LocalDateTime, LocalDateTime)}
     * 而不是自己拼「已过期」：本类里的快照按定义只存<b>有效持有</b>的租约（状态恒为 {@code ACTIVE}），
     * 所以状态维度在这里恒为「有效」、判据退化成时间比较——但走同一个方法，
     * 契约那句「self-fencing 必须用组合判据」才是可执行的（复核 D9 指出此前该方法在 main 里零调用）。</p>
     *
     * @param now 当前时刻
     * @return 必须停采返回 {@code true}
     */
    boolean mustSelfFence(LocalDateTime now) {
        return LeaseEpochRules.needsSelfFence(LeaseState.ACTIVE, leaseExpireAt, now);
    }
}
