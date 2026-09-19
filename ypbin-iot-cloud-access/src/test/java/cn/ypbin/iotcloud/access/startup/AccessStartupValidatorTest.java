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
import feign.Request;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * access 启动自检测试（三条都是 fail-fast）。
 *
 * <p>为什么必须 fail-fast 而不是像 business 那样只记日志：node-id 为空会让所有副本注册成
 * 同一个节点、续约周期过短会让节点反复把自己 fencing、重领间隔为 0 会每个 tick 都重领——
 * 三者都会让「租约归属」这套机制失效，而它们都只在启动瞬间可判。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
class AccessStartupValidatorTest {

    @Test
    @DisplayName("默认配置通过：node-id 有值、续约周期是续约最坏耗时的 2 倍以上、重领间隔为正")
    void shouldPassWithDefaultConfiguration() {
        AccessProperties properties = configured();

        assertThat(validator(properties, defaultOptions()).errors()).isEmpty();
    }

    @Test
    @DisplayName("node-id 为空 → 报错（会让所有副本注册成同一个节点）")
    void shouldRejectBlankNodeId() {
        AccessProperties properties = configured();
        properties.setNodeId("  ");

        assertThat(validator(properties, defaultOptions()).errors()).singleElement().asString()
            .contains("node-id 不能为空");
    }

    @Test
    @DisplayName("续约周期小于最坏耗时的 2 倍 → 报错，并给出最坏耗时与要求倍数")
    void shouldRejectTooShortRenewInterval() {
        AccessProperties properties = configured();
        properties.setRenewIntervalMs(7_000L);

        assertThat(validator(properties, defaultOptions()).errors()).singleElement().asString()
            .contains("必须 ≥ 一次续约最坏耗时")
            .contains("1000ms + read 3000ms = 4000ms")
            .contains("≥" + (4_000 * AccessStartupValidator.SAFETY_FACTOR) + "ms");
    }

    @Test
    @DisplayName("门禁读的是**生效的** Feign 超时 Bean：宿主把 read 调到 30s 后，默认 10s 周期必须被判不合法")
    void shouldUseEffectiveRequestOptionsInsteadOfConstants() {
        AccessProperties properties = configured();
        // 默认常量下 10s 周期是合法的（10s ≥ 2×4s）；宿主覆盖成 read=30s 后就不再合法
        Request.Options overridden = new Request.Options(1_000, TimeUnit.MILLISECONDS, 30_000,
            TimeUnit.MILLISECONDS, true);

        assertThat(validator(properties, defaultOptions()).errors()).isEmpty();
        assertThat(validator(properties, overridden).errors()).singleElement().asString()
            .contains("31000ms");
    }

    @Test
    @DisplayName("主上下文没有 Request.Options Bean 时回落契约常量（Feign 子上下文的默认值就是它）")
    void shouldFallBackToContractConstantsWhenOptionsAbsent() {
        AccessProperties properties = configured();
        properties.setRenewIntervalMs(7_000L);

        assertThat(validator(properties, null).errors()).singleElement().asString()
            .contains("= " + (LeaseFeignConfiguration.CONNECT_TIMEOUT_MS
                + LeaseFeignConfiguration.READ_TIMEOUT_MS) + "ms");
    }

    @Test
    @DisplayName("重领间隔为 0/负数 → 报错（会让每个调度 tick 都重领一次）")
    void shouldRejectNonPositiveAcquireInterval() {
        AccessProperties properties = configured();
        properties.setAcquireIntervalMs(0L);

        assertThat(validator(properties, defaultOptions()).errors()).singleElement().asString()
            .contains("acquire-interval-ms 必须为正数");
    }

    @Test
    @DisplayName("多条都错 → 一次列全（不早退掩盖后面的）")
    void shouldReportAllIssues() {
        AccessProperties properties = configured();
        properties.setNodeId("");
        properties.setRenewIntervalMs(1_000L);
        properties.setAcquireIntervalMs(-1L);

        assertThat(validator(properties, defaultOptions()).errors()).hasSize(3);
    }

    @Test
    @DisplayName("校验入口：通过时不抛；有问题时抛且消息里带全部问题")
    void afterPropertiesSetShouldFailFastOnIssues() {
        AccessProperties ok = configured();
        assertThatCode(() -> validator(ok, defaultOptions()).afterPropertiesSet())
            .doesNotThrowAnyException();

        AccessProperties bad = configured();
        bad.setNodeId("");
        bad.setRenewIntervalMs(1_000L);
        assertThatThrownBy(() -> validator(bad, defaultOptions()).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("access 启动自检未通过")
            .hasMessageContaining("node-id 不能为空")
            .hasMessageContaining("renew-interval-ms");
    }

    private AccessProperties configured() {
        AccessProperties properties = new AccessProperties();
        properties.setNodeId("access-1");
        return properties;
    }

    private Request.Options defaultOptions() {
        return new Request.Options(LeaseFeignConfiguration.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS,
            LeaseFeignConfiguration.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS, true);
    }

    /** 用 {@link StaticListableBeanFactory} 造一个「有/没有该 Bean」的 ObjectProvider。 */
    private ObjectProvider<Request.Options> providerOf(Request.Options options) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        if (options != null) {
            beanFactory.addBean("leaseRequestOptions", options);
        }
        return beanFactory.getBeanProvider(Request.Options.class);
    }

    private AccessStartupValidator validator(AccessProperties properties, Request.Options options) {
        return new AccessStartupValidator(properties, providerOf(options));
    }
}
