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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.iotcloud.access.config.AccessProperties;
import cn.ypbin.iotcloud.api.lease.config.LeaseFeignConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * access 启动自检测试（两条都是 fail-fast）。
 *
 * <p>为什么这两条必须 fail-fast 而不是像 business 那样只记日志：node-id 为空会让所有副本注册成
 * 同一个节点、续约周期过短会让节点反复把自己 fencing——两者都会让「租约归属」这套机制失效，
 * 而它们都只在启动瞬间可判。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
class AccessStartupValidatorTest {

    @Test
    @DisplayName("默认配置通过：node-id 有值、续约周期是续约最坏耗时的 2 倍以上")
    void shouldPassWithDefaultConfiguration() {
        AccessProperties properties = new AccessProperties();
        properties.setNodeId("access-1");

        assertThat(new AccessStartupValidator(properties).errors()).isEmpty();
    }

    @Test
    @DisplayName("node-id 为空 → 报错（会让所有副本注册成同一个节点）")
    void shouldRejectBlankNodeId() {
        AccessProperties properties = new AccessProperties();
        properties.setNodeId("  ");

        assertThat(new AccessStartupValidator(properties).errors()).singleElement().asString()
            .contains("node-id 不能为空");
    }

    @Test
    @DisplayName("续约周期小于最坏耗时的 2 倍 → 报错，并在消息里给出最坏耗时与要求倍数")
    void shouldRejectTooShortRenewInterval() {
        AccessProperties properties = new AccessProperties();
        properties.setNodeId("access-1");
        properties.setRenewIntervalMs(7_000L);

        int worstCase = LeaseFeignConfiguration.CONNECT_TIMEOUT_MS + LeaseFeignConfiguration.READ_TIMEOUT_MS;
        assertThat(new AccessStartupValidator(properties).errors()).singleElement().asString()
            .contains("必须 ≥ 一次续约最坏耗时")
            .contains("1000ms + read 3000ms = " + worstCase + "ms")
            .contains("≥" + (worstCase * AccessStartupValidator.SAFETY_FACTOR) + "ms");
    }

    @Test
    @DisplayName("两条都错 → 一次列全（不早退掩盖第二条）")
    void shouldReportAllIssues() {
        AccessProperties properties = new AccessProperties();
        properties.setNodeId("");
        properties.setRenewIntervalMs(1_000L);

        assertThat(new AccessStartupValidator(properties).errors()).hasSize(2);
    }

    @Test
    @DisplayName("校验入口：通过时不抛；有问题时抛且消息里带全部问题")
    void afterPropertiesSetShouldFailFastOnIssues() {
        AccessProperties ok = new AccessProperties();
        ok.setNodeId("access-1");
        assertThatCode(() -> new AccessStartupValidator(ok).afterPropertiesSet()).doesNotThrowAnyException();

        AccessProperties bad = new AccessProperties();
        bad.setNodeId("");
        bad.setRenewIntervalMs(1_000L);
        assertThatThrownBy(() -> new AccessStartupValidator(bad).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("access 启动自检未通过")
            .hasMessageContaining("node-id 不能为空")
            .hasMessageContaining("renew-interval-ms");
    }
}
