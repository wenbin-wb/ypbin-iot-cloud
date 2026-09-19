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

import cn.ypbin.iotcloud.access.lease.AccessLeaseManager;
import cn.ypbin.iotcloud.access.link.TenantLinkManager;
import cn.ypbin.iotcloud.api.lease.ILeaseClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * access 的租约状态机装配。
 *
 * <p>{@code @ConditionalOnMissingBean} 是留的替换缝：P4b 接上协议栈后，宿主可以用自己的
 * {@link TenantLinkManager}（真断链）覆盖现在的日志实现，而不需要改这里的判定逻辑。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Configuration(proxyBeanMethods = false)
public class AccessLeaseConfiguration {

    /**
     * 租约状态机（注册/领取/续约/self-fencing）。
     *
     * @param leaseClient   business 侧契约客户端
     * @param linkManager   采集链路控制端口
     * @param properties    本节点参数
     * @param meterRegistry 指标注册表
     * @return 租约状态机
     */
    @Bean
    @ConditionalOnMissingBean
    public AccessLeaseManager accessLeaseManager(ILeaseClient leaseClient, TenantLinkManager linkManager,
            AccessProperties properties, MeterRegistry meterRegistry) {
        return new AccessLeaseManager(leaseClient, linkManager, properties, meterRegistry);
    }
}
