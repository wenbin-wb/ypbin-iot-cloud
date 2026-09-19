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

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 租约参数默认值测试。
 *
 * <p>这些默认值不是「随便给的」：{@code ttl} 必须大于 access 的续约周期（10s），
 * {@code scan-interval-ms} 就是「节点退出 → 待接管」的最坏延迟。默认值被改动时本用例会红，
 * 逼着改的人回来读一遍注释。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
class LeasePropertiesTest {

    @Test
    @DisplayName("默认值：启用、ttl 30s（> 续约周期 10s）、扫描 15s、可分配租户为空")
    void shouldExposeSafeDefaults() {
        LeaseProperties properties = new LeaseProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getScanIntervalMs()).isEqualTo(15_000L);
        assertThat(properties.getAssignableTenantIds()).isEmpty();
    }

    @Test
    @DisplayName("属性可覆盖（配置绑定走的就是这些 setter）")
    void shouldAllowOverrides() {
        LeaseProperties properties = new LeaseProperties();

        properties.setEnabled(false);
        properties.setTtl(Duration.ofSeconds(5));
        properties.setScanIntervalMs(1_000L);
        properties.setAssignableTenantIds(List.of(7L, 9L));

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getTtl()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.getScanIntervalMs()).isEqualTo(1_000L);
        assertThat(properties.getAssignableTenantIds()).containsExactly(7L, 9L);
    }
}
