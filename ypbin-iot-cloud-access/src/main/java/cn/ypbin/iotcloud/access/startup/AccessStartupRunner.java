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
package cn.ypbin.iotcloud.access.startup;

import cn.ypbin.iotcloud.access.config.AccessProperties;
import cn.ypbin.iotcloud.access.lease.AccessLeaseManager;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 启动握手：注册 → 领取，失败即让应用启动失败。
 *
 * <p>可通过 {@code ypbin.access.startup-handshake-enabled=false} 关掉（只用于「验装配」的测试；
 * 生产必须开启，关掉时节点零采集）。</p>
 *
 * <p>为什么放在 {@link ApplicationRunner} 而不是构造器里：它要走网络（Feign），
 * 放在上下文刷新期间会把「网络问题」和「装配问题」混在一起；放在 runner 里语义清楚——
 * <b>上下文装配好了，但节点还不能开始采集，因为还没拿到租户</b>。
 * 抛异常会让 {@code SpringApplication.run} 失败退出，这正是契约 §6 的 P4 硬要求
 * （{@code register} 的任何非 {@code code=200} 都必须当启动失败）。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Component
@ConditionalOnProperty(prefix = AccessProperties.PREFIX, name = "startup-handshake-enabled",
    havingValue = "true", matchIfMissing = true)
public class AccessStartupRunner implements ApplicationRunner {

    private final AccessLeaseManager leaseManager;

    /**
     * 构造启动握手。
     *
     * @param leaseManager 租约状态机
     */
    public AccessStartupRunner(AccessLeaseManager leaseManager) {
        this.leaseManager = leaseManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        leaseManager.start();
    }
}
