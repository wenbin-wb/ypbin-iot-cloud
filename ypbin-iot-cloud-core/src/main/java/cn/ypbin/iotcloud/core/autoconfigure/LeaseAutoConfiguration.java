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

import cn.ypbin.iotcloud.core.lease.InMemoryLeaseStore;
import cn.ypbin.iotcloud.core.lease.LeaseExpiryScanner;
import cn.ypbin.iotcloud.core.lease.LeaseProperties;
import cn.ypbin.iotcloud.core.lease.LeaseService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 租约维护的自动装配（M0a）。
 *
 * <p>只有 {@code ypbin.lease.enabled=false} 时才整体不装配：租约维护是 business 的核心职责之一，
 * 关掉它只应出现在「本地只跑业务接口」的场景。</p>
 *
 * <p>⚠️ <b>属性 bean 不在这里注册</b>：{@link LeaseProperties} 由无条件的
 * {@link LeasePropertiesAutoConfiguration} 提供。若把 {@code @EnableConfigurationProperties}
 * 放在本类上，{@code enabled=false} 会让属性 bean 一起消失，导致任何注入它的组件（如 business 的
 * 启动自检）**启动失败**——「关掉租约」就变成了「把服务弄挂」（独立复核实测过一次）。</p>
 *
 * <p>三个 bean 都是 {@code @ConditionalOnMissingBean}：M0b 换成数据库实现（或测试里换成假实现）时，
 * 宿主只要自己声明同类型 bean 即可覆盖，不需要改这里。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = LeaseProperties.PREFIX, name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class LeaseAutoConfiguration {

    /**
     * 归属存储（M0a 内存实现；M0b 换成 {@code tenant_node_assignment} 表）。
     *
     * @return 内存存储
     */
    @Bean
    @ConditionalOnMissingBean
    public InMemoryLeaseStore inMemoryLeaseStore() {
        return new InMemoryLeaseStore();
    }

    /**
     * 租约维护服务。
     *
     * @param store      归属存储
     * @param properties 租约参数
     * @return 租约维护服务
     */
    @Bean
    @ConditionalOnMissingBean
    public LeaseService leaseService(InMemoryLeaseStore store, LeaseProperties properties) {
        return new LeaseService(store, properties);
    }

    /**
     * 失效扫描器（需要宿主开启 {@code @EnableScheduling}，见 business 启动类）。
     *
     * @param leaseService 租约维护服务
     * @return 失效扫描器
     */
    @Bean
    @ConditionalOnMissingBean
    public LeaseExpiryScanner leaseExpiryScanner(LeaseService leaseService) {
        return new LeaseExpiryScanner(leaseService);
    }
}
