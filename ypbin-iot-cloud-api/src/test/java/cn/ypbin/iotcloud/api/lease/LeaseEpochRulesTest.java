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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 台账版本号与租约的契约级规则测试（IOT-CLOUD-SPEC.md §3.1②③）。
 *
 * @author wenbin
 * @since 2026-09-18
 */
class LeaseEpochRulesTest {

    @Test
    @DisplayName("快照仅在新于本地时才可采用（防订阅期间的更新被旧快照覆盖）")
    void shouldAdoptSnapshotOnlyWhenNewer() {
        assertThat(LeaseEpochRules.shouldAdoptSnapshot(5L, 6L)).isTrue();
        assertThat(LeaseEpochRules.shouldAdoptSnapshot(5L, 5L)).isFalse();
        assertThat(LeaseEpochRules.shouldAdoptSnapshot(6L, 5L)).isFalse();
    }

    @Test
    @DisplayName("事件仅在严格新于本地时才应用，相等或更旧一律丢弃（防旧事件复活已删设备）")
    void shouldApplyEventOnlyWhenNewer() {
        assertThat(LeaseEpochRules.shouldApplyEvent(5L, 6L)).isTrue();
        assertThat(LeaseEpochRules.shouldApplyEvent(5L, 5L)).isFalse();
        assertThat(LeaseEpochRules.shouldApplyEvent(5L, 4L)).isFalse();
    }

    @Test
    @DisplayName("版本号递增恰好 +1，且到达上限时显式失败（不允许回绕成负数）")
    void nextEpochShouldBeMonotonicAndFailOnOverflow() {
        assertThat(LeaseEpochRules.nextEpoch(0L)).isEqualTo(1L);
        assertThat(LeaseEpochRules.nextEpoch(41L)).isEqualTo(42L);
        assertThatThrownBy(() -> LeaseEpochRules.nextEpoch(Long.MAX_VALUE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("台账版本号已达上限");
    }

    @Test
    @DisplayName("组合判据：状态失效**或**租约已过期都必须 self-fencing（只看状态会漏判「ACTIVE 但已过期」）")
    void needsSelfFenceShouldCoverBothStateAndExpiry() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 18, 12, 0, 0);
        // ACTIVE 且未过期 → 不 fencing
        assertThat(LeaseEpochRules.needsSelfFence(LeaseState.ACTIVE, now.plusSeconds(5), now)).isFalse();
        // ACTIVE 但已过期 → 必须 fencing（长 GC 停顿/网络分区下的真实形态）
        assertThat(LeaseEpochRules.needsSelfFence(LeaseState.ACTIVE, now.minusSeconds(1), now)).isTrue();
        // 状态已失效 → 必须 fencing（即使到期时间还没到）
        assertThat(LeaseEpochRules.needsSelfFence(LeaseState.PENDING_TAKEOVER, now.plusSeconds(5), now))
            .isTrue();
        // 拿不到状态或到期时间 → 一律 fencing（fail-safe）
        assertThat(LeaseEpochRules.needsSelfFence(null, now.plusSeconds(5), now)).isTrue();
        assertThat(LeaseEpochRules.needsSelfFence(LeaseState.ACTIVE, null, now)).isTrue();
    }

    @Test
    @DisplayName("快照采用后只回放严格新于快照的事件（否则订阅期间的新变更被旧快照覆盖）")
    void shouldReplayOnlyNewerEventsAfterSnapshot() {
        assertThat(LeaseEpochRules.shouldReplayAfterSnapshot(7L, 8L)).isTrue();
        assertThat(LeaseEpochRules.shouldReplayAfterSnapshot(7L, 7L)).isFalse();
        assertThat(LeaseEpochRules.shouldReplayAfterSnapshot(8L, 7L)).isFalse();
    }

    @Test
    @DisplayName("非 ACTIVE 租约都必须 self-fencing")
    void shouldFenceUnlessActive() {
        assertThat(LeaseEpochRules.shouldFence(LeaseState.ACTIVE)).isFalse();
        assertThat(LeaseEpochRules.shouldFence(LeaseState.PENDING_TAKEOVER)).isTrue();
        assertThat(LeaseEpochRules.shouldFence(LeaseState.RELEASED)).isTrue();
    }

    @Test
    @DisplayName("租约到期判定：到点即失效（含恰好相等）")
    void leaseExpiryShouldBeInclusive() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 18, 12, 0, 0);
        assertThat(LeaseEpochRules.isLeaseExpired(now.minusSeconds(1), now)).isTrue();
        assertThat(LeaseEpochRules.isLeaseExpired(now, now)).isTrue();
        assertThat(LeaseEpochRules.isLeaseExpired(now.plusSeconds(1), now)).isFalse();
    }
}
