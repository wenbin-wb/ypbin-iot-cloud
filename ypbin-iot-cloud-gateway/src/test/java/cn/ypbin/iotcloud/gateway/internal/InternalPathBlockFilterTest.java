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
package cn.ypbin.iotcloud.gateway.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * 内部端点封堵的<b>行为</b>测试。
 *
 * <p>为什么必须在网关侧测：网关路由是 {@code Path=/business/**} + {@code StripPrefix=1}，
 * 因此 {@code /business/internal/...} 会被改写成下游的 {@code /internal/...} —— 这是「本该不可达
 * 却实际可达」的路径，独立复核实测过它确实能穿过网关。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
class InternalPathBlockFilterTest {

    private final InternalPathBlockFilter filter = new InternalPathBlockFilter(new ObjectMapper());

    @Test
    @DisplayName("带服务前缀的内部路径（StripPrefix 后被改写的那条）必须被拦，且不进入后续链路")
    void shouldBlockInternalPathBehindServicePrefix() {
        MockServerWebExchange exchange = MockServerWebExchange
            .from(MockServerHttpRequest.get("/business/internal/lease/epochs"));

        assertBlocked(exchange);
    }

    @Test
    @DisplayName("不带前缀的内部路径同样被拦")
    void shouldBlockBareInternalPath() {
        assertBlocked(MockServerWebExchange.from(MockServerHttpRequest.get("/internal/lease/epochs")));
    }

    @Test
    @DisplayName("普通业务路径必须放行（不能把封堵写成拦截一切）")
    void shouldPassThroughBusinessPath() {
        MockServerWebExchange exchange = MockServerWebExchange
            .from(MockServerHttpRequest.get("/business/devices"));
        AtomicBoolean chained = new AtomicBoolean();

        filter.filter(exchange, ignored -> {
            chained.set(true);
            return Mono.empty();
        }).block();

        assertThat(chained).isTrue();
    }

    private void assertBlocked(MockServerWebExchange exchange) {
        AtomicBoolean chained = new AtomicBoolean();

        filter.filter(exchange, ignored -> {
            chained.set(true);
            return Mono.empty();
        }).block();

        assertThat(chained).as("被拦的请求不得进入后续链路（否则仍会到达下游）").isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange.getResponse().getBodyAsString().block())
            .contains("\"code\":404")
            .contains("接口不存在");
    }
}
