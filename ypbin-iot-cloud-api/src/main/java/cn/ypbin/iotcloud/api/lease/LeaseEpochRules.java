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

/**
 * 台账版本号（epoch）与租约的<b>契约级判定规则</b>。
 *
 * <p>这些规则是 access 与 business 必须共同遵守的语义（IOT-CLOUD-SPEC.md §3.1②③），
 * 放在契约模块而不是各自实现里，避免两侧各写一份而悄悄分叉：</p>
 * <ul>
 *   <li><b>快照准入</b>：仅当 {@code snapshotEpoch > localEpoch} 才可采用（防订阅期间的新事件被旧快照覆盖）；</li>
 *   <li><b>事件应用</b>：仅当 {@code eventEpoch > localEpoch} 才应用，比本地旧的<b>丢弃</b>
 *       （防「旧事件复活已删设备」）；</li>
 *   <li><b>单调递增</b>：台账变更必须在同一事务里把 epoch +1，不得回退或跳变；</li>
 *   <li><b>self-fencing 判据</b>：租约不再有效（被撤销/被接管）时节点必须断链停采。</li>
 * </ul>
 *
 * @author wenbin
 * @since 2026-09-18
 */
public final class LeaseEpochRules {

    private LeaseEpochRules() {
    }

    /**
     * 判断是否可采用全量快照。
     *
     * @param localEpoch    节点本地版本号
     * @param snapshotEpoch 快照版本号
     * @return 快照版本<b>严格大于</b>本地版本时返回 {@code true}
     */
    public static boolean shouldAdoptSnapshot(long localEpoch, long snapshotEpoch) {
        return snapshotEpoch > localEpoch;
    }

    /**
     * 判断是否应应用变更事件。
     *
     * @param localEpoch 节点本地版本号
     * @param eventEpoch 事件版本号
     * @return 事件版本<b>严格大于</b>本地版本时返回 {@code true}；相等或更旧一律丢弃
     */
    public static boolean shouldApplyEvent(long localEpoch, long eventEpoch) {
        return eventEpoch > localEpoch;
    }

    /**
     * 计算下一个版本号（台账变更时与变更<b>同事务</b>调用）。
     *
     * @param currentEpoch 当前版本号
     * @return 当前版本号 + 1
     * @throws IllegalArgumentException 当前版本号已到 {@link Long#MAX_VALUE}（继续递增会溢出并被回绕成负数，
     *                                  致使所有事件被判为「旧」而永久丢弃，必须显式失败）
     */
    public static long nextEpoch(long currentEpoch) {
        if (currentEpoch == Long.MAX_VALUE) {
            throw new IllegalArgumentException("台账版本号已达上限，无法继续递增：epoch=" + currentEpoch);
        }
        return currentEpoch + 1;
    }

    /**
     * self-fencing 判据：租约是否已不再有效。
     *
     * @param state 租约状态
     * @return 非 {@link LeaseState#ACTIVE} 时返回 {@code true}（节点必须立即断链并停止采集）
     */
    public static boolean shouldFence(LeaseState state) {
        return state != LeaseState.ACTIVE;
    }

    /**
     * 判断租约是否已过期（{@code leaseExpireAt < now} 即判定节点死亡，§3.1①）。
     *
     * @param leaseExpireAt 租约到期时间
     * @param now           当前时间
     * @return 到期时间不晚于当前时间时返回 {@code true}
     */
    public static boolean isLeaseExpired(LocalDateTime leaseExpireAt, LocalDateTime now) {
        return !leaseExpireAt.isAfter(now);
    }
}
