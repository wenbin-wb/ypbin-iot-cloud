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
package cn.ypbin.iotcloud.core.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iotcloud.core.lease.InMemoryLeaseStore;
import cn.ypbin.iotcloud.core.lease.LeaseExpiryScanner;
import cn.ypbin.iotcloud.core.lease.LeaseProperties;
import cn.ypbin.iotcloud.core.lease.LeaseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 租约自动装配测试。
 *
 * <p>为什么值得单独测：这三个 bean 是 business 能否提供租约能力的唯一入口。
 * 「默认装上」、「关掉时不装」、「用户自定义实现可覆盖」三条都要有用例——
 * 否则一次条件写错（例如 matchIfMissing 漏了）会让 business 起来却没有任何租约能力，
 * 而所有单测都还是绿的。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
class LeaseAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(LeaseAutoConfiguration.class));

    @Test
    @DisplayName("默认装配：存储 / 服务 / 扫描器 / 参数四件套")
    void shouldRegisterAllBeansByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(InMemoryLeaseStore.class);
            assertThat(context).hasSingleBean(LeaseService.class);
            assertThat(context).hasSingleBean(LeaseExpiryScanner.class);
            assertThat(context).hasSingleBean(LeaseProperties.class);
        });
    }

    @Test
    @DisplayName("ypbin.lease.enabled=false 时整体不装配（本地只想跑业务接口的场景）")
    void shouldNotRegisterWhenDisabled() {
        runner.withPropertyValues("ypbin.lease.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(LeaseService.class);
            assertThat(context).doesNotHaveBean(LeaseExpiryScanner.class);
        });
    }

    @Test
    @DisplayName("宿主自己声明了存储实现时，自动装配让位（M0b 换数据库实现的接缝）")
    void shouldBackOffWhenStoreProvided() {
        InMemoryLeaseStore custom = new InMemoryLeaseStore();
        runner.withBean(InMemoryLeaseStore.class, () -> custom).run(context -> assertThat(context)
            .getBean(InMemoryLeaseStore.class).isSameAs(custom));
    }
}
