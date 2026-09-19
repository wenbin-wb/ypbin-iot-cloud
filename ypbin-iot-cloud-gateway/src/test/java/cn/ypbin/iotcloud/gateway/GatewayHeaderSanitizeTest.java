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

import cn.ypbin.starter.gateway.autoconfigure.GatewayProperties;
import cn.ypbin.starter.gateway.filter.HeaderSanitizeGlobalFilter;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 剥离伪造身份头的<b>行为用例</b>（IOT-CLOUD-SPEC.md §4.4-2「必须配用例」）。
 *
 * <p>只用「配置里有没有这个头」断言是不够的：那证明的是配置形状，不是过滤器的实际行为。
 * 这里用随包发布的 `application.yml` 绑定出 {@link GatewayProperties}，
 * 构造 <b>starter 真实的</b> {@link HeaderSanitizeGlobalFilter} 跑一遍过滤链，
 * 再读链尾（= 下游）看到的请求头。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
class GatewayHeaderSanitizeTest {

    private static List<String> sanitizeHeaders;

    @BeforeAll
    static void bindSanitizeHeaders() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml"));
        MutablePropertySources propertySources = new MutablePropertySources();
        sources.forEach(propertySources::addLast);
        Binder binder = new Binder(ConfigurationPropertySources.from(propertySources));
        GatewayProperties properties = binder
            .bind("ypbin.gateway", Bindable.of(GatewayProperties.class))
            .orElseThrow(() -> new IllegalStateException("无法绑定 ypbin.gateway 配置"));
        sanitizeHeaders = properties.getHeaderSanitize().getHeaders();
    }

    /** 跑一遍清洗过滤器，返回「下游」看到的请求头快照。 */
    private ServerWebExchange passThroughSanitizeFilter(MockServerHttpRequest request) {
        HeaderSanitizeGlobalFilter filter = new HeaderSanitizeGlobalFilter(sanitizeHeaders);
        AtomicReference<ServerWebExchange> seenByDownstream = new AtomicReference<>();
        GatewayFilterChain chain = exchange -> {
            seenByDownstream.set(exchange);
            return Mono.empty();
        };
        filter.filter(MockServerWebExchange.from(request), chain).block();
        return seenByDownstream.get();
    }

    @Test
    @DisplayName("客户端自带的 6 个身份头（含 X-Gateway-Signed）必须全部被剥掉，客户端值不得到达下游")
    void clientSuppliedIdentityHeadersMustBeStripped() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/business/ping")
            .header("X-User-Id", "999")
            .header("X-User-Name", "forged")
            .header("X-Tenant-Id", "forged-tenant")
            .header("X-Dept-Id", "forged-dept")
            .header("X-Roles", "ROLE_ADMIN")
            .header("X-Gateway-Signed", "FORGED-SIGNED")
            .build();

        ServerWebExchange downstream = passThroughSanitizeFilter(request);

        for (String header : List.of("X-User-Id", "X-User-Name", "X-Tenant-Id", "X-Dept-Id",
                "X-Roles", "X-Gateway-Signed")) {
            assertThat(downstream.getRequest().getHeaders().getFirst(header))
                .as("客户端伪造的 %s 不得到达下游", header)
                .isNull();
        }
        // 非身份头不受影响
        assertThat(downstream.getRequest().getHeaders().getFirst("X-Request-Id")).isNull();
    }

    @Test
    @DisplayName("X-Gateway-Signed 必须在剥离名单里（starter 默认名单不含它，漏了就重新开洞）")
    void trustedSourceHeaderMustBeInSanitizeList() {
        assertThat(sanitizeHeaders).contains("X-Gateway-Signed");
    }
}
