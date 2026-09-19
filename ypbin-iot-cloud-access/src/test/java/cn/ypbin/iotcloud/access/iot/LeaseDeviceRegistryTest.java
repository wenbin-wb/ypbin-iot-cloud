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
package cn.ypbin.iotcloud.access.iot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.core.spi.ChangeType;
import cn.ypbin.iot.core.spi.DeviceChange;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 设备注册表测试（绑定/解绑的变更通道）。
 *
 * <p>最要紧的一条：revision 必须<b>严格递增</b>——iot-starter 的 {@code IotLifecycle.onDeviceChange}
 * 会丢弃 {@code revision <= applied} 的变更。递减或重复的 revision 会让运行期的绑定/解绑被静默忽略，
 * 表现为「该断的链没断」，而日志里什么都不会说。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
class LeaseDeviceRegistryTest {

    @Test
    @DisplayName("新增/删除都会发变更，且 revision 严格递增（否则会被框架丢弃）")
    void revisionsMustBeStrictlyIncreasing() {
        LeaseDeviceRegistry registry = new LeaseDeviceRegistry();
        List<DeviceChange> changes = new ArrayList<>();
        registry.addChangeListener(changes::add);
        DeviceSpec device = device("dev-1");

        assertThat(registry.addDevices(List.of(device))).isEqualTo(1);
        assertThat(registry.loadAll()).hasSize(1);
        assertThat(registry.boundDeviceIds()).containsExactly("dev-1");
        assertThat(registry.removeDevices(List.of(device))).isEqualTo(1);
        assertThat(registry.loadAll()).isEmpty();

        assertThat(changes).extracting(DeviceChange::type).containsExactly(ChangeType.ADD, ChangeType.REMOVE);
        assertThat(changes).extracting(DeviceChange::revision).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("重复登记同一设备不再发变更（避免无谓的重复绑定）")
    void duplicateAddShouldNotPublishAgain() {
        LeaseDeviceRegistry registry = new LeaseDeviceRegistry();
        List<DeviceChange> changes = new ArrayList<>();
        registry.addChangeListener(changes::add);

        assertThat(registry.addDevices(List.of(device("dev-1")))).isEqualTo(1);
        assertThat(registry.addDevices(List.of(device("dev-1")))).isZero();
        assertThat(registry.removeDevices(List.of(device("dev-1")))).isEqualTo(1);
        assertThat(registry.removeDevices(List.of(device("dev-1")))).isZero();

        assertThat(changes).hasSize(2);
    }

    @Test
    @DisplayName("未登记的设备不会被误删（删除只对它登记过的生效）")
    void removeUnknownDeviceShouldBeNoop() {
        LeaseDeviceRegistry registry = new LeaseDeviceRegistry();

        assertThat(registry.removeDevices(List.of(device("ghost")))).isZero();
        assertThat(registry.loadAll()).isEmpty();
    }

    @Test
    @DisplayName("F5：并发增删时 revision 唯一且按送达顺序递增（框架按 revision 丢旧变更，乱序=静默忽略）")
    void concurrentChangesMustKeepMonotonicRevisions() throws InterruptedException {
        LeaseDeviceRegistry registry = new LeaseDeviceRegistry();
        List<DeviceChange> delivered = new CopyOnWriteArrayList<>();
        registry.addChangeListener(delivered::add);
        int threads = 4;
        int perThread = 25;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int index = t;
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    String deviceId = "dev-" + index + "-" + i;
                    registry.addDevices(List.of(device(deviceId)));
                    registry.removeDevices(List.of(device(deviceId)));
                }
            }, "registry-worker-" + t);
            worker.start();
            workers.add(worker);
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(10_000);
        }

        assertThat(delivered).hasSize(threads * perThread * 2);
        // 送达顺序里的 revision 必须严格递增（实现把「定序 + 通知」都放在同一把锁里）
        long previous = 0L;
        for (DeviceChange change : delivered) {
            assertThat(change.revision()).isGreaterThan(previous);
            previous = change.revision();
        }
        assertThat(registry.loadAll()).isEmpty();
    }

    @Test
    @DisplayName("监听器抛异常不中断其它监听器，也不把异常抛给调用方（但会被记录）")
    void listenerFailureMustNotBreakOthers() {
        LeaseDeviceRegistry registry = new LeaseDeviceRegistry();
        List<String> seen = new ArrayList<>();
        registry.addChangeListener(change -> {
            throw new IllegalStateException("故意失败");
        });
        registry.addChangeListener(change -> seen.add(change.device().deviceId()));

        assertThatCode(() -> registry.addDevices(List.of(device("dev-1")))).doesNotThrowAnyException();
        assertThat(seen).containsExactly("dev-1");
    }

    private DeviceSpec device(String deviceId) {
        return new DeviceSpec(deviceId, deviceId, ProtocolCode.of("tcp"), "conn-1", "", Duration.ZERO, Map.of());
    }
}
