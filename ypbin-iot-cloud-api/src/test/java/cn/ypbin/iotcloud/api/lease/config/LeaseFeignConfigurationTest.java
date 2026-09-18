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
package cn.ypbin.iotcloud.api.lease.config;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iotcloud.common.config.InternalProperties;
import feign.Request;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 租约 Feign 配置测试：显式超时必须存在，且读超时必须<b>远小于续约周期</b>。
 *
 * <p>读超时若接近或超过续约周期（默认 10s，§3.1①），一次卡顿就会让续约跨过到期时间，
 * 节点的租户被判失效并接管——这是「配置错误会直接造成采集中断」的典型，必须有门禁。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
class LeaseFeignConfigurationTest {

    /** 续约周期（毫秒），与契约文档/IOT-CLOUD-SPEC.md §3.1① 的默认值一致。 */
    private static final int RENEW_INTERVAL_MS = 10_000;

    private final LeaseFeignConfiguration configuration = new LeaseFeignConfiguration();

    @Test
    @DisplayName("必须显式配置连接与读超时（禁止无超时默认客户端）")
    void shouldDeclareExplicitTimeouts() {
        assertThat(LeaseFeignConfiguration.CONNECT_TIMEOUT_MS).isPositive();
        assertThat(LeaseFeignConfiguration.READ_TIMEOUT_MS).isPositive();
    }

    @Test
    @DisplayName("读超时必须远小于续约周期，否则续约会被卡过到期时间")
    void readTimeoutMustBeFarBelowRenewInterval() {
        assertThat(LeaseFeignConfiguration.READ_TIMEOUT_MS).isLessThan(RENEW_INTERVAL_MS / 2);
    }

    @Test
    @DisplayName("Feign 请求选项按常量取值")
    void requestOptionsShouldUseDeclaredTimeouts() {
        Request.Options options = configuration.leaseRequestOptions();
        assertThat(options.connectTimeoutMillis()).isEqualTo(LeaseFeignConfiguration.CONNECT_TIMEOUT_MS);
        assertThat(options.readTimeoutMillis()).isEqualTo(LeaseFeignConfiguration.READ_TIMEOUT_MS);
        assertThat(options.isFollowRedirects()).isTrue();
        // 以毫秒为单位传入：若误用 TimeUnit.SECONDS，3 秒会变成 3000 秒，续约必然跨过到期时间
        assertThat(options.readTimeoutMillis())
            .isEqualTo(TimeUnit.MILLISECONDS.convert(LeaseFeignConfiguration.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("拦截器 Bean 使用传入的凭证配置（装配正确）")
    void interceptorBeanShouldUseProperties() {
        InternalProperties properties = new InternalProperties();
        properties.setToken("t");
        assertThat(configuration.internalTokenRequestInterceptor(properties))
            .isInstanceOf(InternalTokenRequestInterceptor.class);
    }
}
