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
package cn.ypbin.iotcloud.core.lease;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iotcloud.api.lease.LeaseState;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 归属存储的语义测试（M0a 内存实现）。
 *
 * <p>这些是 {@link LeaseService} 依赖的<b>存储层约定</b>：未知租户的版本号初值、未知租户递增时的行为、
 * 容量缺省（不限）、以及「全部归属按租户 ID 排序」——排序是日志与断言确定性的前提。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
class InMemoryLeaseStoreTest {

    private final InMemoryLeaseStore store = new InMemoryLeaseStore();

    @Test
    @DisplayName("未知租户：版本号是初值，nextEpoch 在初值上 +1（防御直接调用存储的场景）")
    void unknownTenantShouldStartFromInitialEpoch() {
        assertThat(store.currentEpoch(7L)).isEqualTo(InMemoryLeaseStore.INITIAL_EPOCH);
        assertThat(store.nextEpoch(7L)).isEqualTo(InMemoryLeaseStore.INITIAL_EPOCH + 1);
        assertThat(store.currentEpoch(7L)).isEqualTo(InMemoryLeaseStore.INITIAL_EPOCH + 1);
    }

    @Test
    @DisplayName("容量缺省 = 不限；显式容量按值生效；未注册容量为 0")
    void capacityShouldSupportUnlimited() {
        store.registerNode("unlimited", null);
        store.registerNode("small", 2);

        assertThat(store.capacityOf("unlimited")).isEqualTo(InMemoryLeaseStore.UNLIMITED_CAPACITY);
        assertThat(store.capacityOf("small")).isEqualTo(2);
        assertThat(store.capacityOf("never-registered")).isZero();
        assertThat(store.isRegistered("small")).isTrue();
        assertThat(store.isRegistered("never-registered")).isFalse();
    }

    @Test
    @DisplayName("保存归属会同步版本号；findAll 按租户 ID 升序；knownTenantIds 反映已出现的租户")
    void saveShouldTrackEpochAndOrder() {
        LocalDateTime expireAt = LocalDateTime.now().plusSeconds(30);
        store.save(new LeaseAssignment(22L, "n1", expireAt, 5L, LeaseState.ACTIVE));
        store.save(new LeaseAssignment(11L, "n1", expireAt, 5L, LeaseState.ACTIVE));

        assertThat(store.findAll()).extracting(LeaseAssignment::tenantId).containsExactly(11L, 22L);
        assertThat(store.currentEpoch(22L)).isEqualTo(5L);
        assertThat(store.knownTenantIds()).containsExactlyInAnyOrder(11L, 22L);
        assertThat(store.find(11L)).isPresent();
        assertThat(store.find(999L)).isEmpty();
    }
}
