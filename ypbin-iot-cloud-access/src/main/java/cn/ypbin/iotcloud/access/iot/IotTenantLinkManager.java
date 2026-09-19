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
import cn.ypbin.iot.core.model.ProbeResult;
import cn.ypbin.iot.spring.autoconfigure.IotLifecycle;
import cn.ypbin.iotcloud.access.config.AccessProperties;
import cn.ypbin.iotcloud.access.link.TenantLinkManager;
import cn.ypbin.starter.core.util.LogSanitizer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 iot-starter 的链路管理实现：<b>租约归属 → 真建链/真断链</b>（P4b）。
 *
 * <p>与 M0a 的 {@code LoggingTenantLinkManager} 的区别：那边「断链」只是把租户从内存集合里摘掉；
 * 这里通过 {@link LeaseDeviceRegistry} 的变更通道驱动 iot-starter 的
 * {@code IotLifecycle.bind/unbind}，<b>真的建立与关闭设备会话</b>——这正是 spec §3.1① 要求的
 * 「旧节点发现自己租约失效必须自己断链停采」的落地形态。</p>
 *
 * <p>建链前先 {@code probe()}（iot-starter README 的建议：先用 probe 打通目标设备）：
 * 探测不可达就不登记，避免把一台连不上的设备当成「已在采集」——那会让上层以为租户在线。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
public class IotTenantLinkManager implements TenantLinkManager {

    private static final Logger log = LoggerFactory.getLogger(IotTenantLinkManager.class);

    /** 指标前缀（与 `LoggingDataSink` 同域）。 */
    static final String METRIC_PREFIX = "iotcloud.access.";

    /** 探测等待相对建链超时的余量（毫秒）：probe 自身受建链超时约束，这里多给 1s 容错。 */
    static final long PROBE_GRACE_MS = 1_000L;

    private final AccessDeviceCatalog catalog;
    private final LeaseDeviceRegistry registry;
    private final IotLifecycle lifecycle;
    private final AccessProperties properties;
    private final Counter bound;
    private final Counter fenced;

    /** 本节点认为自己在采的租户（= 持有租约且已发起绑定）。 */
    private final Set<Long> collecting = ConcurrentHashMap.newKeySet();

    /**
     * 构造链路管理。
     *
     * @param catalog       设备目录（配置）
     * @param registry      设备注册表（绑定/解绑的变更通道）
     * @param lifecycle     iot-starter 的生命周期编排（probe/bind/unbind）
     * @param properties    节点参数
     * @param meterRegistry 指标注册表
     */
    public IotTenantLinkManager(AccessDeviceCatalog catalog, LeaseDeviceRegistry registry,
            IotLifecycle lifecycle, AccessProperties properties, MeterRegistry meterRegistry) {
        this.catalog = catalog;
        this.registry = registry;
        this.lifecycle = lifecycle;
        this.properties = properties;
        this.bound = Counter.builder(METRIC_PREFIX + "link.bound")
            .description("真正绑定（建链）的设备次数").register(meterRegistry);
        this.fenced = Counter.builder(METRIC_PREFIX + "link.fenced")
            .description("因租约失效/撤销而解绑（断链）的设备次数").register(meterRegistry);
    }

    @Override
    public void startCollecting(Long tenantId) {
        List<DeviceSpec> devices = catalog.devicesOf(tenantId);
        collecting.add(tenantId);
        if (devices.isEmpty()) {
            log.warn("租户名下没有配置设备，无法采集：tenantId={}（检查 ypbin.access.devices；"
                + "M0a 的设备清单来自配置，M0b 换成台账表）", LogSanitizer.sanitize(tenantId));
            return;
        }
        List<DeviceSpec> reachable = new ArrayList<>();
        for (DeviceSpec device : devices) {
            if (probe(device)) {
                reachable.add(device);
            }
        }
        int added = registry.addDevices(reachable);
        if (added > 0) {
            bound.increment(added);
        }
        log.info("开始采集租户：tenantId={} 配置设备={} 可建链={} 本次新绑定={}",
            LogSanitizer.sanitize(tenantId), devices.size(), reachable.size(), added);
    }

    @Override
    public void fence(Long tenantId, String reason) {
        collecting.remove(tenantId);
        int removed = registry.removeDevices(catalog.devicesOf(tenantId));
        if (removed > 0) {
            fenced.increment(removed);
            log.warn("租户已断链停采（真断链）：tenantId={} 设备数={} reason={}",
                LogSanitizer.sanitize(tenantId), removed, LogSanitizer.sanitize(reason));
        }
    }

    @Override
    public void fenceAll(String reason) {
        collecting.clear();
        int removed = registry.removeDevices(registry.loadAll());
        if (removed > 0) {
            fenced.increment(removed);
            log.warn("本节点整体断链停采（真断链）：设备数={} reason={}", removed,
                LogSanitizer.sanitize(reason));
        }
    }

    @Override
    public boolean isCollecting(Long tenantId) {
        return collecting.contains(tenantId);
    }

    @Override
    public Set<Long> collectingTenants() {
        return Set.copyOf(collecting);
    }

    /**
     * 建链前探测：不可达就不绑定。
     *
     * <p>失败原因如实记录（不静默降级）：探测失败多半是端点/权限/网络问题，把它当「已采集」
     * 会让上层看到「租户在线」而实际没有任何数据。</p>
     */
    private boolean probe(DeviceSpec device) {
        Optional<ConnectionSpec> spec = catalog.connectionSpec(device.connectionId());
        if (spec.isEmpty()) {
            log.warn("设备缺少连接定义，拒绝绑定：deviceId={} connectionId={}",
                LogSanitizer.sanitize(device.deviceId()), LogSanitizer.sanitize(device.connectionId()));
            return false;
        }
        try {
            ProbeResult result = lifecycle.probe(spec.get())
                .get(properties.getDeviceConnectTimeout().toMillis() + PROBE_GRACE_MS, TimeUnit.MILLISECONDS);
            if (!result.reachable()) {
                log.warn("设备探测不可达，拒绝绑定：deviceId={} endpoint={} 原因={}",
                    LogSanitizer.sanitize(device.deviceId()), spec.get().endpoint(),
                    LogSanitizer.sanitize(result.failureReason()));
                return false;
            }
            log.debug("设备探测通过：deviceId={} 耗时={}ms", LogSanitizer.sanitize(device.deviceId()),
                result.elapsed().toMillis());
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("设备探测被中断，拒绝绑定：deviceId={}", LogSanitizer.sanitize(device.deviceId()));
            return false;
        } catch (ExecutionException | TimeoutException ex) {
            log.warn("设备探测异常，拒绝绑定：deviceId={}", LogSanitizer.sanitize(device.deviceId()), ex);
            return false;
        }
    }
}
