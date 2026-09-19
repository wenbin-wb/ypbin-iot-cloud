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
     * self-fencing 判据（仅看状态）：租约是否已不再有效。
     *
     * <p>⚠️ <b>单独使用会漏判</b>：状态仍是 {@code ACTIVE} 但 {@code leaseExpireAt} 已过期的租约，
     * 本方法返回 {@code false}。判断「是否必须 fencing」请用
     * {@link #needsSelfFence(LeaseState, LocalDateTime, LocalDateTime)}（状态与到期时间的组合判据）。</p>
     *
     * @param state 租约状态（{@code null} 视为无效，fail-safe）
     * @return 非 {@link LeaseState#ACTIVE} 时返回 {@code true}
     */
    public static boolean shouldFence(LeaseState state) {
        return state != LeaseState.ACTIVE;
    }

    /**
     * self-fencing 的<b>完整组合判据</b>（IOT-CLOUD-SPEC.md §3.1① 的硬要求）：
     * 状态失效 <b>或</b> 租约已过期，二者取或。
     *
     * <p>为什么要封装成单一方法：只判状态会漏掉「ACTIVE 但已过期」（长 GC 停顿/网络分区下正是这种形态），
     * 只判到期时间会漏掉「被 business 撤销但尚未到期」。调用方只该用这一个入口。</p>
     *
     * @param state         租约状态（{@code null} 视为失效）
     * @param leaseExpireAt 租约到期时间（{@code null} 视为已失效——拿不到到期时间就不该继续采集）
     * @param now           当前时间
     * @return 必须断链停采时返回 {@code true}
     */
    public static boolean needsSelfFence(LeaseState state, LocalDateTime leaseExpireAt,
            LocalDateTime now) {
        if (shouldFence(state)) {
            return true;
        }
        return leaseExpireAt == null || isLeaseExpired(leaseExpireAt, now);
    }

    /**
     * 快照采用后的事件回放判据（§3.1② 第Ⅲ步）：仅回放版本<b>严格新于快照</b>的事件。
     *
     * <p>拉取快照期间到达的事件必须缓存（不得直接应用），采用快照后再按本判据回放，
     * 否则会被旧快照覆盖掉订阅期间的新变更。</p>
     *
     * @param snapshotEpoch 已采用快照的版本号
     * @param eventEpoch    缓存事件的版本号
     * @return 应回放时返回 {@code true}
     */
    public static boolean shouldReplayAfterSnapshot(long snapshotEpoch, long eventEpoch) {
        return eventEpoch > snapshotEpoch;
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
