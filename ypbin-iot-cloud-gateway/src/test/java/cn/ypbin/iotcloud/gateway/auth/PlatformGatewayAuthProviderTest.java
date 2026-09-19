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
package cn.ypbin.iotcloud.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.starter.gateway.auth.GatewayAuthResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/**
 * M0a 认证器行为测试：<b>必须 fail-closed</b>。
 *
 * @author wenbin
 * @since 2026-09-18
 */
class PlatformGatewayAuthProviderTest {

    private final PlatformGatewayAuthProvider provider = new PlatformGatewayAuthProvider();

    private GatewayAuthResult authenticate(String authorization) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/business/ping");
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return provider.authenticate(MockServerWebExchange.from(request.build())).block();
    }

    @Test
    @DisplayName("无访问令牌 → 未认证，且给出可读原因")
    void shouldRejectWhenTokenAbsent() {
        GatewayAuthResult result = authenticate(null);
        assertThat(result.isAuthenticated()).isFalse();
        assertThat(result.getMessage()).isNotBlank();
    }

    @Test
    @DisplayName("M0a 即使带了令牌也一律拒绝（认证服务 M0b 才接入），不得出现「配了鉴权却放行」")
    void shouldRejectWhenAuthServiceNotWiredYet() {
        GatewayAuthResult result = authenticate("Bearer fake-token");
        assertThat(result.isAuthenticated()).isFalse();
        assertThat(result.getMessage()).contains("M0b");
    }

    @Test
    @DisplayName("空串与纯空白令牌同样按未认证处理")
    void shouldRejectBlankToken() {
        assertThat(authenticate("   ").isAuthenticated()).isFalse();
        assertThat(authenticate("").isAuthenticated()).isFalse();
    }
}
