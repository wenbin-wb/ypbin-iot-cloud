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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cn.ypbin.iotcloud.access.link.LoggingTenantLinkManager;
import cn.ypbin.iotcloud.access.link.TenantLinkManager;
import cn.ypbin.iotcloud.api.lease.ILeaseClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配测试：默认实现与「宿主替换」这条接缝是否真的成立。
 *
 * <p>复核实测过一个假缝：实现类原先是无条件 {@code @Component}，宿主再定义自己的
 * {@code TenantLinkManager} 会直接 {@code NoUniqueBeanDefinitionException}——
 * 也就是说「P4b 换真断链实现」这件事当时根本做不到。现在实现由
 * {@code @Bean @ConditionalOnMissingBean} 提供，本用例钉住它可被替换。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
class AccessLeaseConfigurationTest {

    @Test
    @DisplayName("默认装配：链路管理用日志实现")
    void shouldProvideLoggingImplementationByDefault() {
        contextRunner().run(context -> assertThat(context).getBean(TenantLinkManager.class)
            .isInstanceOf(LoggingTenantLinkManager.class));
    }

    @Test
    @DisplayName("宿主提供自己的实现时自动让位（P4b 换真断链实现的接缝）")
    void shouldBackOffWhenHostProvidesTenantLinkManager() {
        contextRunner().withUserConfiguration(HostTenantLinkManagerConfiguration.class)
            .run(context -> assertThat(context).getBean(TenantLinkManager.class)
                .isInstanceOf(HostTenantLinkManagerConfiguration.HostLinkManager.class));
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
            .withBean(ILeaseClient.class, () -> mock(ILeaseClient.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(AccessProperties.class, AccessProperties::new)
            // 用 AutoConfigurations 而不是 withUserConfiguration：这样才能复现「自动配置晚于用户 bean」
            // 的顺序保证（替换缝成立的前提，见 AccessLeaseConfiguration 的类注释）
            .withConfiguration(AutoConfigurations.of(AccessLeaseConfiguration.class));
    }

    /** 模拟 P4b 的宿主：自己声明一个真的会断链的链路管理实现。 */
    @Configuration(proxyBeanMethods = false)
    static class HostTenantLinkManagerConfiguration {

        @Bean
        TenantLinkManager hostTenantLinkManager() {
            return new HostLinkManager();
        }

        /** 宿主实现占位（真实断链实现会在 P4b 写）。 */
        static class HostLinkManager implements TenantLinkManager {

            @Override
            public void startCollecting(Long tenantId) {
                // 占位：测试只关心「谁被装配」
            }

            @Override
            public void fence(Long tenantId, String reason) {
                // 占位：测试只关心「谁被装配」
            }

            @Override
            public void fenceAll(String reason) {
                // 占位：测试只关心「谁被装配」
            }

            @Override
            public boolean isCollecting(Long tenantId) {
                return false;
            }

            @Override
            public Set<Long> collectingTenants() {
                return Set.of();
            }
        }
    }
}
