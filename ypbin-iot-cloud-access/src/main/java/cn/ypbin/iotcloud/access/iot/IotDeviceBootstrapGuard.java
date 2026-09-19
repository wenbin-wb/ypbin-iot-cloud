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

import cn.ypbin.iot.spring.autoconfigure.IotProperties;
import org.springframework.beans.factory.InitializingBean;

/**
 * 启动自检：拒绝「引入了 iot-starter 却关掉设备引导」这种配置。
 *
 * <p>为什么必须 fail-fast：iot-starter 的设备引导（就绪期 {@code loadAll()} 建链）与
 * <b>变更通道接线</b>（{@code addChangeListener}）写在<b>同一段</b>受 {@code devices.enabled} 控制的代码里。
 * 关掉它不会报任何错，但会让「租约驱动的绑定/解绑」全部无人接收——表现为
 * <b>该断的链没断、该建的链没建，而日志里什么都没有</b>（复核实测：{@code sessionCount=0} 且注册表仍有设备）。</p>
 *
 * <p>确实要停用协议栈时的正确做法：<b>不引入 iot-starter 依赖</b>，让 M0a 的日志实现接管
 * （那条路是显式可见的）。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
public class IotDeviceBootstrapGuard implements InitializingBean {

    private final IotProperties properties;

    /**
     * 构造自检。
     *
     * @param properties iot-starter 配置
     */
    public IotDeviceBootstrapGuard(IotProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.devices().isEnabled()) {
            throw new IllegalStateException("ypbin.iot.devices.enabled=false 会让租约驱动的绑定/解绑"
                + "静默失效（设备引导与变更通道接线在同一段代码里）：要么把它设为 true，"
                + "要么去掉 iot-starter 依赖走 M0a 的日志实现——不要「引入协议栈但关掉设备引导」");
        }
    }
}
