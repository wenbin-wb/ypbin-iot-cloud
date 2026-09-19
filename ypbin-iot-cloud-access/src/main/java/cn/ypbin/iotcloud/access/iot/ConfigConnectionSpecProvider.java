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
import cn.ypbin.iot.core.spi.ConnectionSpecProvider;
import java.util.Optional;

/**
 * 连接定义提供者：把配置里的「连接 → 端点 + 超时」交给 iot-starter 解析。
 *
 * <p>iot-starter 的 {@code IotLifecycle.bind} 会遍历所有 {@link ConnectionSpecProvider} 找
 * connectionId 对应的连接定义；找不到就直接拒绝绑定（并在日志里说明），不会静默降级。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
public class ConfigConnectionSpecProvider implements ConnectionSpecProvider {

    private final AccessDeviceCatalog catalog;

    /**
     * 构造连接定义提供者。
     *
     * @param catalog 设备目录
     */
    public ConfigConnectionSpecProvider(AccessDeviceCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public Optional<ConnectionSpec> find(String connectionId) {
        return catalog.connectionSpec(connectionId);
    }
}
