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
package cn.ypbin.iotcloud.access.config;

import cn.ypbin.iot.core.spi.ConnectionSpecProvider;
import cn.ypbin.iot.core.spi.DataSink;
import cn.ypbin.iot.spring.autoconfigure.IotLifecycle;
import cn.ypbin.iot.spring.autoconfigure.IotProperties;
import cn.ypbin.iotcloud.access.iot.AccessDeviceCatalog;
import cn.ypbin.iotcloud.access.iot.ConfigConnectionSpecProvider;
import cn.ypbin.iotcloud.access.iot.IotDeviceBootstrapGuard;
import cn.ypbin.iotcloud.access.iot.IotTenantLinkManager;
import cn.ypbin.iotcloud.access.iot.LeaseDeviceRegistry;
import cn.ypbin.iotcloud.access.iot.LoggingDataSink;
import cn.ypbin.iotcloud.access.link.TenantLinkManager;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * P4b：接上 iot-starter 的装配（classpath 有 {@link IotLifecycle} 时生效）。
 *
 * <p>这是一个<b>覆盖</b>装配：它提供的 {@link TenantLinkManager} 是「真建链/真断链」的实现，
 * 而 M0a 的 {@code AccessLeaseConfiguration} 里那个日志实现靠 {@code @ConditionalOnMissingBean} 让位。
 * 两个都是自动配置，且本类声明 {@code before = AccessLeaseConfiguration.class}
 * ——自动配置的顺序保证才有意义（把 {@code @ConditionalOnMissingBean} 写在用户 {@code @Configuration}
 * 里是假保险，母仓教训三十一）。</p>
 *
 * <p>三个宿主 SPI（{@code DeviceRegistry} / {@code ConnectionSpecProvider} / {@code DataSink}）
 * 由本类提供，正是 iot-starter 要求的接入方式（它的 README §接入：宿主只提供这三样）。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@AutoConfiguration(before = AccessLeaseConfiguration.class)
@ConditionalOnClass(IotLifecycle.class)
@EnableConfigurationProperties(AccessProperties.class)
public class AccessIotAutoConfiguration {

    /**
     * 设备目录（配置 → iot-starter 契约对象）。
     *
     * @param properties 节点参数
     * @return 设备目录
     */
    @Bean
    @ConditionalOnMissingBean
    public AccessDeviceCatalog accessDeviceCatalog(AccessProperties properties) {
        return new AccessDeviceCatalog(properties);
    }

    /**
     * 设备注册表：登记当前持有的租户名下的设备，并作为绑定/解绑的变更通道。
     *
     * @return 设备注册表
     */
    @Bean
    @ConditionalOnMissingBean
    public LeaseDeviceRegistry leaseDeviceRegistry() {
        return new LeaseDeviceRegistry();
    }

    /**
     * 连接定义提供者（iot-starter 的 SPI）。
     *
     * @param catalog 设备目录
     * @return 连接定义提供者
     */
    @Bean
    @ConditionalOnMissingBean
    public ConnectionSpecProvider configConnectionSpecProvider(AccessDeviceCatalog catalog) {
        return new ConfigConnectionSpecProvider(catalog);
    }

    /**
     * 数据出口（iot-starter 的 SPI）：M0a 只计数与打日志，M0b 换成上报 business。
     *
     * @param meterRegistry 指标注册表
     * @return 数据出口
     */
    @Bean
    @ConditionalOnMissingBean
    public DataSink loggingDataSink(MeterRegistry meterRegistry) {
        return new LoggingDataSink(meterRegistry);
    }

    /**
     * 启动自检：拒绝「引入了 iot-starter 却关掉设备引导」（那会让断链静默失效）。
     *
     * @param properties iot-starter 配置
     * @return 自检器
     */
    @Bean
    @ConditionalOnMissingBean
    public IotDeviceBootstrapGuard iotDeviceBootstrapGuard(IotProperties properties) {
        return new IotDeviceBootstrapGuard(properties);
    }

    /**
     * 链路管理（覆盖 M0a 的日志实现）：租约归属 → 真建链/真断链。
     *
     * @param catalog       设备目录
     * @param registry      设备注册表
     * @param lifecycle     iot-starter 生命周期编排
     * @param properties    节点参数
     * @param meterRegistry 指标注册表
     * @return 链路管理
     */
    @Bean
    @ConditionalOnMissingBean
    public TenantLinkManager iotTenantLinkManager(AccessDeviceCatalog catalog, LeaseDeviceRegistry registry,
            IotLifecycle lifecycle, AccessProperties properties, MeterRegistry meterRegistry) {
        return new IotTenantLinkManager(catalog, registry, lifecycle, properties, meterRegistry);
    }
}
