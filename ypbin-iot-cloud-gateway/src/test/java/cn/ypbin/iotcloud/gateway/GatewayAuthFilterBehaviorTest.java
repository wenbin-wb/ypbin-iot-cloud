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
package cn.ypbin.iotcloud.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iotcloud.gateway.auth.PlatformGatewayAuthProvider;
import cn.ypbin.starter.gateway.autoconfigure.GatewayProperties;
import cn.ypbin.starter.gateway.filter.GatewayAuthGlobalFilter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * 网关鉴权的<b>行为用例</b>（计划 P2 的必备用例）。
 *
 * <p>三条必须成立：① 无令牌 → HTTP 200 + {@code R.code=401} 且<b>未到达下游</b>；
 * ② 白名单路径匿名 → 到达下游且不带任何身份头；③ 白名单路径带令牌 → 同样不带身份头
 * （M0a 认证服务未接入，识别失败按匿名放行）。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
class GatewayAuthFilterBehaviorTest {

    private static final List<String> SANITIZE_HEADERS = List.of("X-User-Id", "X-User-Name",
        "X-Tenant-Id", "X-Dept-Id", "X-Roles", "X-Gateway-Signed");

    /** 链尾记录：是否到达下游、看到的交换。 */
    private static final class DownstreamProbe {

        private final AtomicBoolean reached = new AtomicBoolean(false);

        private final AtomicReference<ServerWebExchange> exchange = new AtomicReference<>();

        GatewayFilterChain chain() {
            return current -> {
                reached.set(true);
                exchange.set(current);
                return Mono.empty();
            };
        }
    }

    /** 与 starter 自身的用例保持同一构造口径（见 ypbin-starter-cloud-gateway 的单测）。 */
    private GatewayAuthGlobalFilter authFilter(List<String> excludePaths) {
        return new GatewayAuthGlobalFilter(new PlatformGatewayAuthProvider(),
            JsonMapper.builder().build(), excludePaths, "X-Gateway-Signed", "");
    }

    @Test
    @DisplayName("无令牌访问受保护路径 → HTTP 200 + R.code=401，且请求不得到达下游")
    void protectedPathWithoutTokenMustBeRejectedBeforeDownstream() {
        DownstreamProbe probe = new DownstreamProbe();
        MockServerWebExchange exchange =
            MockServerWebExchange.from(MockServerHttpRequest.get("/business/ping").build());

        authFilter(List.of("/actuator/health")).filter(exchange, probe.chain()).block();

        assertThat(exchange.getResponse().getStatusCode())
            .as("统一异常口径：HTTP 200")
            .isEqualTo(HttpStatus.OK);
        assertThat(exchange.getResponse().getBodyAsString().block())
            .as("靠 R.code 区分失败，且必须给出可读原因")
            .contains("401")
            .contains("未携带访问令牌");
        assertThat(probe.reached.get()).as("被鉴权拦下的请求绝不能转发到下游").isFalse();
    }

    @Test
    @DisplayName("白名单路径匿名访问 → 到达下游，且不带任何身份头")
    void whitelistedPathMustPassWithoutIdentityHeaders() {
        DownstreamProbe probe = new DownstreamProbe();
        MockServerWebExchange exchange =
            MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health").build());

        authFilter(List.of("/actuator/health")).filter(exchange, probe.chain()).block();

        assertThat(probe.reached.get()).as("白名单路径必须匿名放行").isTrue();
        for (String header : SANITIZE_HEADERS) {
            assertThat(probe.exchange.get().getRequest().getHeaders().getFirst(header)).isNull();
        }
    }

    @Test
    @DisplayName("白名单路径带令牌（M0a 识别失败）→ 仍到达下游，且不得注入任何身份头")
    void whitelistedPathWithTokenMustNotInjectIdentityHeaders() {
        DownstreamProbe probe = new DownstreamProbe();
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/actuator/health")
                .header("Authorization", "Bearer fake-token").build());

        authFilter(List.of("/actuator/health")).filter(exchange, probe.chain()).block();

        assertThat(probe.reached.get()).isTrue();
        for (String header : SANITIZE_HEADERS) {
            assertThat(probe.exchange.get().getRequest().getHeaders().getFirst(header)).isNull();
        }
    }

    @Test
    @DisplayName("starter 默认放行列表含健康探针与文档；本仓 yml 显式覆盖时必须抄回这些默认项")
    void starterDefaultExcludePathsShouldCoverHealthAndDocs() {
        assertThat(new GatewayProperties().getAuth().getExcludePaths())
            .as("显式写 exclude-paths 会整体替换该默认列表（实测漏抄会让文档路径变 401）")
            .anyMatch(path -> path.startsWith("/actuator/health"))
            .anyMatch(path -> path.startsWith("/v3/api-docs"));
    }
}
