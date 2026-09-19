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

import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.spi.ChangeType;
import cn.ypbin.iot.core.spi.DeviceChange;
import cn.ypbin.iot.core.spi.DeviceRegistry;
import cn.ypbin.starter.core.util.LogSanitizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 设备注册表：<b>只登记本节点当前持有的租户名下的设备</b>，并把它作为「绑定/解绑」的驱动通道交给 iot-starter。
 *
 * <p>与 iot-starter 的协作方式（读它的 {@code IotLifecycle} 源码得到，不是猜的）：</p>
 * <ol>
 *   <li>应用就绪（{@code ApplicationReadyEvent}）时，lifecycle 调各注册表的 {@link #loadAll()} 并逐个
 *       {@code bind}——此时本节点的租约握手（{@code ApplicationRunner}）已经完成，所以「该绑哪些设备」
 *       就是当时持有的租户名下的设备；</li>
 *   <li>运行期增删设备走 {@link #addChangeListener} 注册的通道（{@code onDeviceChange}），
 *       而它<b>按 revision 去重并丢弃旧变更</b>（{@code change.revision() <= applied} 直接忽略）——
 *       所以本类必须保证 revision <b>严格递增</b>，否则运行期的绑定/解绑会被静默忽略。</li>
 * </ol>
 *
 * <p>这正是「self-fencing 真执行」的落点：租约被撤销/过期时，把该租户的设备从注册表里 <b>REMOVE</b>，
 * lifecycle 就会真正断链。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
public class LeaseDeviceRegistry implements DeviceRegistry {

    private static final Logger log = LoggerFactory.getLogger(LeaseDeviceRegistry.class);

    private final Map<String, DeviceSpec> bound = new LinkedHashMap<>();
    private final List<Consumer<DeviceChange>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong revision = new AtomicLong();

    @Override
    public List<DeviceSpec> loadAll() {
        synchronized (bound) {
            return List.copyOf(bound.values());
        }
    }

    @Override
    public void addChangeListener(Consumer<DeviceChange> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * 登记一批设备并通知变更通道（新增）。
     *
     * @param devices 要绑定的设备
     * @return 本次真正新增的设备数
     */
    public int addDevices(List<DeviceSpec> devices) {
        List<DeviceChange> changes = new ArrayList<>();
        synchronized (bound) {
            for (DeviceSpec device : devices) {
                if (bound.putIfAbsent(device.deviceId(), device) == null) {
                    changes.add(new DeviceChange(ChangeType.ADD, device, revision.incrementAndGet()));
                }
            }
            // F5（复核指出）：revision **与通知**都要在锁内——框架按 revision 去重丢弃旧变更
            // （`revision <= applied` 直接忽略），所以同一设备的变更必须按 revision 顺序送达；
            // 只在锁内定序、锁外通知，会让两个线程的同一设备变更乱序到达而后者被丢弃。
            // 代价：监听器（框架的 bind/unbind）在持锁期间执行——它只做会话创建/关闭的调度，
            // 属于有界短操作；换来的是「该断的链一定断」这个硬保证，值得。
            changes.forEach(this::notifyListeners);
        }
        return changes.size();
    }

    /**
     * 注销一批设备并通知变更通道（删除）——这就是「断链」的触发点。
     *
     * @param devices 要解绑的设备
     * @return 本次真正移除的设备数
     */
    public int removeDevices(List<DeviceSpec> devices) {
        List<DeviceChange> changes = new ArrayList<>();
        synchronized (bound) {
            for (DeviceSpec device : devices) {
                if (bound.remove(device.deviceId()) != null) {
                    changes.add(new DeviceChange(ChangeType.REMOVE, device, revision.incrementAndGet()));
                }
            }
            changes.forEach(this::notifyListeners);
        }
        return changes.size();
    }

    /** 当前登记的设备标识（只读快照）。 */
    public List<String> boundDeviceIds() {
        synchronized (bound) {
            return List.copyOf(bound.keySet());
        }
    }

    private void notifyListeners(DeviceChange change) {
        for (Consumer<DeviceChange> listener : listeners) {
            try {
                listener.accept(change);
            } catch (RuntimeException ex) {
                // 不静默：变更通道是绑定/解绑的唯一路径，吞掉它会让「该断的链没断」
                log.error("设备变更通知失败（type={} deviceId={}）：绑定/解绑可能未生效",
                    change.type(), LogSanitizer.sanitize(change.device().deviceId()), ex);
            }
        }
    }
}
