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
package cn.ypbin.iotcloud.core.lease;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iotcloud.api.lease.LeaseState;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 租约失效扫描器测试。
 *
 * <p>只做两件事：调度入口能不能触发失效判定、没有过期租约时是否安静（不制造噪音日志）。
 * 判定逻辑本身在 {@link LeaseServiceTest} 里覆盖。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
class LeaseExpiryScannerTest {

    private static final long TENANT = 11L;
    private static final String NODE = "access-1";

    private InMemoryLeaseStore store;
    private LeaseService service;
    private LeaseExpiryScanner scanner;

    @BeforeEach
    void setUp() {
        LeaseProperties properties = new LeaseProperties();
        properties.setTtl(Duration.ofSeconds(30));
        properties.setAssignableTenantIds(List.of(TENANT));
        store = new InMemoryLeaseStore();
        service = new LeaseService(store, properties);
        scanner = new LeaseExpiryScanner(service);
    }

    @Test
    @DisplayName("扫描到过期租约 → 置为待接管")
    void scanShouldMarkExpiredLease() {
        service.register(NODE, 1);
        service.acquire(NODE);
        LeaseAssignment held = store.find(TENANT).orElseThrow();
        store.save(new LeaseAssignment(TENANT, held.accessNode(), LocalDateTime.now().minusSeconds(5),
            held.epoch(), LeaseState.ACTIVE));

        scanner.scan();

        assertThat(store.find(TENANT).orElseThrow().state()).isEqualTo(LeaseState.PENDING_TAKEOVER);
    }

    @Test
    @DisplayName("扫描周期的两处默认值必须一致：注解里的字面量 vs 属性默认值（防静默漂移）")
    void scheduledDefaultMustMatchPropertyDefault() throws Exception {
        Scheduled scheduled = LeaseExpiryScanner.class.getMethod("scan").getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
            .isEqualTo("${ypbin.lease.scan-interval-ms:" + new LeaseProperties().getScanIntervalMs() + "}");
    }

    @Test
    @DisplayName("无可接管租约时扫描是安静的（不改变任何状态）")
    void scanShouldBeQuietWhenNothingExpired() {
        service.register(NODE, 1);
        service.acquire(NODE);

        scanner.scan();

        assertThat(store.find(TENANT).orElseThrow().state()).isEqualTo(LeaseState.ACTIVE);
    }
}
