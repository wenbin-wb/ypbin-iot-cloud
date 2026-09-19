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
package cn.ypbin.iotcloud.access;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iotcloud.access.config.AccessProperties;
import cn.ypbin.iotcloud.access.lease.AccessLeaseManager;
import cn.ypbin.iotcloud.access.lease.LeaseRenewScheduler;
import cn.ypbin.iotcloud.access.link.TenantLinkManager;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * access 的真实上下文测试：装配、配置绑定、指标注册。
 *
 * <p>为什么用真实上下文：租约客户端的装配（{@code @EnableFeignClients} + Feign 契约 bean）、
 * 配置绑定（{@code @EnableConfigurationProperties}）、指标注册表、调度器 bean 都属于「装配正确性」，
 * 只有真起上下文才验得到。</p>
 *
 * <p><b>本用例显式关闭启动握手</b>（{@code ypbin.access.startup-handshake-enabled=false}）：
 * 否则 runner 会真的经 Feign 去连 business（测试里没有 business，会连接被拒→启动失败）。
 * 握手本身的行为与 fail-fast 语义由 {@code AccessStartupRunnerTest} 覆盖；
 * 真实的 simple 发现 + 出站令牌是否打通，属 P5 的五进程端到端验收。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@SpringBootTest(properties = {
    "ypbin.internal.token=test-internal-token",
    "ypbin.access.node-id=access-context-test",
    "ypbin.access.startup-handshake-enabled=false"
})
class AccessContextTest {

    @Autowired
    private AccessLeaseManager leaseManager;

    @Autowired
    private TenantLinkManager linkManager;

    @Autowired
    private AccessProperties accessProperties;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private LeaseRenewScheduler scheduler;

    @Test
    @DisplayName("上下文启动：配置已绑定、租约状态机/链路端口/指标/调度器都装配好（握手按开关跳过）")
    void contextShouldStartWithAllBeansWired() {
        assertThat(accessProperties.getNodeId()).isEqualTo("access-context-test");
        assertThat(accessProperties.isStartupHandshakeEnabled()).isFalse();
        assertThat(leaseManager.heldTenants()).isEmpty();
        assertThat(linkManager.collectingTenants()).isEmpty();
        assertThat(meterRegistry.find("iotcloud.lease.held").gauge()).isNotNull();
        assertThat(meterRegistry.find("iotcloud.lease.renew.success").counter()).isNotNull();
        assertThat(meterRegistry.find("iotcloud.lease.self_fenced").counter()).isNotNull();
        assertThat(scheduler).isNotNull();
    }

    @Test
    @DisplayName("调度注解里的默认值必须与 AccessProperties 的默认值一致（防静默漂移）")
    void scheduledDefaultMustMatchPropertyDefault() throws Exception {
        Scheduled scheduled = LeaseRenewScheduler.class.getMethod("renew").getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
            .isEqualTo("${ypbin.access.renew-interval-ms:" + new AccessProperties().getRenewIntervalMs() + "}");
    }
}
