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

import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iotcloud.access.config.AccessProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.util.StringUtils;

/**
 * 设备目录：把配置里的「租户 → 设备 → 连接」整理成 iot-starter 的契约对象。
 *
 * <p>为什么要单独一层：iot-starter 的宿主 SPI 要的是 {@link DeviceSpec} 与 {@link ConnectionSpec}，
 * 而配置是扁平的；把「翻译」集中在这里，既避免 SPI 实现里到处 new，也让「配置非法」在启动期就能被发现
 * （缺 deviceId/uri、协议码非法等）。</p>
 *
 * <p>本类<b>无状态且只读</b>：绑定/解绑的运行时状态在 {@link LeaseDeviceRegistry}。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
public class AccessDeviceCatalog {

    /** M0a/P4b 只接 TCP 透传（MQTT 需要 EMQX，而 M0a 明确不接 EMQX）。 */
    static final String PROTOCOL_TCP = "tcp";

    private final Map<Long, List<DeviceSpec>> devicesByTenant;
    private final Map<String, ConnectionSpec> connections;

    /**
     * 构造设备目录。
     *
     * @param properties 节点参数（含设备清单与超时）
     * @throws IllegalArgumentException 配置非法（缺字段/协议码非法/端点 URI 非法）
     */
    public AccessDeviceCatalog(AccessProperties properties) {
        Map<Long, List<DeviceSpec>> byTenant = new TreeMap<>();
        Map<String, ConnectionSpec> specs = new LinkedHashMap<>();
        Map<String, Long> connectionOwners = new LinkedHashMap<>();
        Set<String> deviceIds = new LinkedHashSet<>();
        ProtocolCode protocol = ProtocolCode.of(PROTOCOL_TCP);
        for (AccessProperties.DeviceEntry entry : properties.getDevices()) {
            requireText(entry.getDeviceId(), "device-id");
            requireText(entry.getUri(), "uri");
            requireText(entry.getConnectionId(), "connection-id");
            if (entry.getTenantId() == null) {
                throw new IllegalArgumentException("设备 " + entry.getDeviceId() + " 缺少 tenant-id："
                    + "租约按租户授予，没有租户就无从判断它该不该被绑定");
            }
            if (entry.getPollIntervalMs() < 0) {
                // 不静默 clamp：负数多半是配置写错（例如把单位当成秒），静默变 0 会掩盖它
                throw new IllegalArgumentException("设备 " + entry.getDeviceId() + " 的 poll-interval-ms 为负（"
                    + entry.getPollIntervalMs() + "）：0 表示不轮询，负数通常是配置写错");
            }
            ConnectionSpec connection = new ConnectionSpec(entry.getConnectionId(), protocol,
                Endpoint.of(entry.getUri()), properties.getDeviceConnectTimeout(),
                properties.getDeviceRequestTimeout(), null, null, Map.of());
            if (!deviceIds.add(entry.getDeviceId())) {
                throw new IllegalArgumentException("device-id 重复：" + entry.getDeviceId()
                    + "：deviceId 是断链与观测的键，重复会让「断一台」变成「断一串」");
            }
            ConnectionSpec previous = specs.putIfAbsent(entry.getConnectionId(), connection);
            if (previous != null) {
                if (!previous.endpoint().equals(connection.endpoint())) {
                    throw new IllegalArgumentException("连接 " + entry.getConnectionId()
                        + " 被两台设备配成了不同端点（" + previous.endpoint() + " vs " + connection.endpoint()
                        + "）：一条连接只能有一个端点");
                }
                // F1（复核实测的真实危害）：跨租户共用一条连接时，撤销租户 A 会连带关掉租户 B 的 socket，
                // 而 B 仍被判为「在采」且不会重连（TCP 适配器是 1:1，见 iot-starter 的 TcpAdapter）。
                // 所以 M0a 直接拒绝这种配置，而不是留一条「看起来能省连接」的坑。
                Long firstTenant = connectionOwners.get(entry.getConnectionId());
                if (!firstTenant.equals(entry.getTenantId())) {
                    throw new IllegalArgumentException("连接 " + entry.getConnectionId() + " 被多个租户共用（tenant "
                        + firstTenant + " 与 " + entry.getTenantId() + "）：撤销一个租户的租约会连带关闭"
                        + "另一个租户的共享 socket，而后者不会自动重连（TCP 连接是 1:1）。"
                        + "请为每个租户配置各自的 connection-id");
                }
            } else {
                connectionOwners.put(entry.getConnectionId(), entry.getTenantId());
            }
            byTenant.computeIfAbsent(entry.getTenantId(), ignored -> new ArrayList<>()).add(
                new DeviceSpec(entry.getDeviceId(), entry.getDeviceId(), protocol, entry.getConnectionId(),
                    "", Duration.ofMillis(entry.getPollIntervalMs()), Map.of()));
        }
        this.devicesByTenant = Map.copyOf(byTenant);
        this.connections = Map.copyOf(specs);
    }

    /**
     * 某租户名下的设备（按 deviceId 排序，保证绑定顺序确定）。
     *
     * @param tenantId 租户 ID
     * @return 设备清单；该租户没有配置设备时返回空列表
     */
    public List<DeviceSpec> devicesOf(Long tenantId) {
        return devicesByTenant.getOrDefault(tenantId, List.of());
    }

    /**
     * 按连接标识找连接定义。
     *
     * @param connectionId 连接标识
     * @return 连接定义；不存在时为空
     */
    public Optional<ConnectionSpec> connectionSpec(String connectionId) {
        return Optional.ofNullable(connections.get(connectionId));
    }

    /** 是否配置了任何设备（空配置时链路管理无事可做，日志要说清）。 */
    public boolean isEmpty() {
        return connections.isEmpty();
    }

    private void requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("设备配置缺少 " + field + "：M0a 的设备清单来自配置，"
                + "字段不全的条目会让建链在运行期静默失败");
        }
    }
}
