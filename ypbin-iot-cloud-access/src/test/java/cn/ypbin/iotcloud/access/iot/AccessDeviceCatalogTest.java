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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.iotcloud.access.config.AccessProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 设备目录测试：配置 → iot-starter 契约对象的翻译，以及非法配置必须在启动期炸出来。
 *
 * @author wenbin
 * @since 2026-09-19
 */
class AccessDeviceCatalogTest {

    @Test
    @DisplayName("按租户归组，并给出连接定义（端点/超时来自配置，不用框架默认值）")
    void shouldGroupDevicesByTenantAndExposeConnections() {
        AccessProperties properties = configured();
        properties.setDeviceConnectTimeout(Duration.ofSeconds(7));
        properties.setDeviceRequestTimeout(Duration.ofSeconds(4));
        properties.getDevices().add(entry(11L, "dev-11", "conn-11", "tcp://127.0.0.1:15002"));
        properties.getDevices().add(entry(11L, "dev-11b", "conn-11", "tcp://127.0.0.1:15002"));
        properties.getDevices().add(entry(22L, "dev-22", "conn-22", "tcp://127.0.0.1:15003"));

        AccessDeviceCatalog catalog = new AccessDeviceCatalog(properties);

        assertThat(catalog.devicesOf(11L)).extracting(device -> device.deviceId())
            .containsExactly("dev-11", "dev-11b");
        assertThat(catalog.devicesOf(22L)).hasSize(1);
        assertThat(catalog.devicesOf(33L)).isEmpty();
        assertThat(catalog.connectionSpec("conn-11")).isPresent();
        assertThat(catalog.connectionSpec("conn-11").orElseThrow().connectTimeout())
            .isEqualTo(Duration.ofSeconds(7));
        assertThat(catalog.connectionSpec("conn-11").orElseThrow().requestTimeout())
            .isEqualTo(Duration.ofSeconds(4));
        assertThat(catalog.connectionSpec("nope")).isEmpty();
        assertThat(catalog.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("缺字段的配置 → 启动期显式报错（而不是运行期静默建链失败）")
    void shouldRejectIncompleteEntries() {
        assertRejected(entry(11L, "", "conn", "tcp://127.0.0.1:15002"), "device-id");
        assertRejected(entry(11L, "dev", "", "tcp://127.0.0.1:15002"), "connection-id");
        assertRejected(entry(11L, "dev", "conn", "  "), "uri");
        assertRejected(entry(null, "dev", "conn", "tcp://127.0.0.1:15002"), "tenant-id");
    }

    @Test
    @DisplayName("端点必须带 scheme（iot-starter 的 Endpoint 契约），否则启动期报错")
    void shouldRejectEndpointWithoutScheme() {
        AccessProperties properties = configured();
        properties.getDevices().add(entry(11L, "dev", "conn", "127.0.0.1:15002"));

        assertThatThrownBy(() -> new AccessDeviceCatalog(properties))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("同一 connectionId 被配成不同端点 → 报错（一条连接只能有一个端点）")
    void shouldRejectConflictingEndpointsForSameConnection() {
        AccessProperties properties = configured();
        properties.getDevices().add(entry(11L, "dev-a", "conn-x", "tcp://127.0.0.1:15002"));
        properties.getDevices().add(entry(22L, "dev-b", "conn-x", "tcp://127.0.0.1:15003"));

        assertThatThrownBy(() -> new AccessDeviceCatalog(properties))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不同端点");
    }

    @Test
    @DisplayName("空配置：目录为空（链路管理会记录「无从采集」而不是假装在采）")
    void emptyConfigurationShouldBeEmptyCatalog() {
        assertThat(new AccessDeviceCatalog(configured()).isEmpty()).isTrue();
    }

    private void assertRejected(AccessProperties.DeviceEntry entry, String field) {
        AccessProperties properties = configured();
        properties.getDevices().add(entry);

        assertThatThrownBy(() -> new AccessDeviceCatalog(properties))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(field);
    }

    private AccessProperties configured() {
        AccessProperties properties = new AccessProperties();
        properties.setNodeId("access-test");
        return properties;
    }

    private AccessProperties.DeviceEntry entry(Long tenantId, String deviceId, String connectionId, String uri) {
        AccessProperties.DeviceEntry entry = new AccessProperties.DeviceEntry();
        entry.setTenantId(tenantId);
        entry.setDeviceId(deviceId);
        entry.setConnectionId(connectionId);
        entry.setUri(uri);
        return entry;
    }
}
