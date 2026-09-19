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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.ypbin.iot.core.model.ProbeResult;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.core.protocol.ProtocolDescriptor;
import cn.ypbin.iot.spring.autoconfigure.IotLifecycle;
import cn.ypbin.iotcloud.access.config.AccessProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 链路管理测试：租约归属 → 真建链/真断链的<b>状态语义</b>（真链接由 e2e 用例负责）。
 *
 * <p>覆盖三条不变量：① 探测不可达就不登记（不能把连不上的设备算作「已采集」）；
 * ② 解绑只对<b>该租户</b>的设备生效（撤销一个租户不该断别人的链）；
 * ③ 节点级 fencing 清空全部。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
class IotTenantLinkManagerTest {

    private static final ProtocolCode TCP = ProtocolCode.of("tcp");

    private IotLifecycle lifecycle;
    private AccessDeviceCatalog catalog;
    private LeaseDeviceRegistry registry;
    private IotTenantLinkManager manager;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        lifecycle = mock(IotLifecycle.class);
        AccessProperties properties = configured();
        catalog = new AccessDeviceCatalog(properties);
        registry = new LeaseDeviceRegistry();
        meterRegistry = new SimpleMeterRegistry();
        manager = new IotTenantLinkManager(catalog, registry, lifecycle, properties, meterRegistry);
    }

    @Test
    @DisplayName("开始采集：探测通过的设备被登记（= 触发框架建链），租户进入采集集合")
    void startCollectingShouldBindReachableDevices() {
        reachable(true);

        manager.startCollecting(11L);

        assertThat(manager.isCollecting(11L)).isTrue();
        assertThat(manager.collectingTenants()).containsExactly(11L);
        assertThat(registry.boundDeviceIds()).containsExactly("dev-11-a", "dev-11-b");
        assertThat(meterRegistry.get("iotcloud.access.link.bound").counter().count()).isEqualTo(2.0d);
    }

    @Test
    @DisplayName("探测不可达 → 不登记（不能让上层以为租户在线），但租户仍算「本节点持有」")
    void unreachableDeviceMustNotBeBound() {
        reachable(false);

        manager.startCollecting(11L);

        assertThat(registry.boundDeviceIds()).isEmpty();
        assertThat(manager.isCollecting(11L)).isTrue();
    }

    @Test
    @DisplayName("探测抛异常 → 不登记且不抛出（单个设备的问题不该让整轮采集崩掉）")
    void probeFailureMustNotPropagate() {
        when(lifecycle.probe(any())).thenReturn(CompletableFuture.failedFuture(
            new IllegalStateException("probe failed")));

        manager.startCollecting(11L);

        assertThat(registry.boundDeviceIds()).isEmpty();
        assertThat(manager.isCollecting(11L)).isTrue();
    }

    @Test
    @DisplayName("租户没有配置设备 → 不登记任何设备，也不抛出（日志说明无从采集）")
    void tenantWithoutDevicesShouldBindNothing() {
        manager.startCollecting(99L);

        assertThat(registry.boundDeviceIds()).isEmpty();
        assertThat(manager.isCollecting(99L)).isTrue();
    }

    @Test
    @DisplayName("撤销一个租户：只解绑它的设备，别的租户不受影响（真断链的边界）")
    void fenceShouldOnlyUnbindThatTenant() {
        reachable(true);
        manager.startCollecting(11L);
        manager.startCollecting(22L);
        assertThat(registry.boundDeviceIds()).hasSize(3);

        manager.fence(11L, "business 判定已被接管");

        assertThat(registry.boundDeviceIds()).containsExactly("dev-22");
        assertThat(manager.isCollecting(11L)).isFalse();
        assertThat(manager.isCollecting(22L)).isTrue();
        assertThat(meterRegistry.get("iotcloud.access.link.fenced").counter().count()).isEqualTo(2.0d);
    }

    @Test
    @DisplayName("节点级 fencing：全部解绑（整体停采）")
    void fenceAllShouldUnbindEverything() {
        reachable(true);
        manager.startCollecting(11L);
        manager.startCollecting(22L);

        manager.fenceAll("business 判定节点失效");

        assertThat(registry.boundDeviceIds()).isEmpty();
        assertThat(manager.collectingTenants()).isEmpty();
    }

    @Test
    @DisplayName("重复开始采集同一租户是幂等的（不重复登记、不重复计数）")
    void startCollectingShouldBeIdempotent() {
        reachable(true);

        manager.startCollecting(11L);
        manager.startCollecting(11L);

        assertThat(registry.boundDeviceIds()).hasSize(2);
        assertThat(meterRegistry.get("iotcloud.access.link.bound").counter().count()).isEqualTo(2.0d);
    }

    private void reachable(boolean value) {
        // 十个参数：code/name/vendor/stackVersion/transport/capabilities/extensions/min/max/attributes
        ProtocolDescriptor descriptor = new ProtocolDescriptor(TCP, "TCP", null, null, null, null, null,
            null, null, null);
        when(lifecycle.probe(any())).thenReturn(CompletableFuture.completedFuture(new ProbeResult(value,
            descriptor, null, Map.of(), value ? null : "connection refused", null, Duration.ofMillis(5))));
    }

    private AccessProperties configured() {
        AccessProperties properties = new AccessProperties();
        properties.setNodeId("access-test");
        properties.setDeviceConnectTimeout(Duration.ofSeconds(1));
        properties.getDevices().add(entry(11L, "dev-11-a", "conn-11"));
        properties.getDevices().add(entry(11L, "dev-11-b", "conn-11"));
        properties.getDevices().add(entry(22L, "dev-22", "conn-22"));
        return properties;
    }

    private AccessProperties.DeviceEntry entry(Long tenantId, String deviceId, String connectionId) {
        AccessProperties.DeviceEntry entry = new AccessProperties.DeviceEntry();
        entry.setTenantId(tenantId);
        entry.setDeviceId(deviceId);
        entry.setConnectionId(connectionId);
        entry.setUri("tcp://127.0.0.1:15002");
        return entry;
    }
}
