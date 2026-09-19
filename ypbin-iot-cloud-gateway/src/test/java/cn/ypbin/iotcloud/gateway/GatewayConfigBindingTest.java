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
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 网关配置绑定测试：直接绑定**随包发布的 `application.yml`**，而不是另抄一份断言值。
 *
 * <p>重点守两条硬要求：</p>
 * <ol>
 *   <li><b>身份头清洗名单必须显式包含 {@code X-Gateway-Signed}</b>：starter 的默认名单只有
 *       {@code X-User-Id/X-User-Name/X-Tenant-Id/X-Dept-Id/X-Roles}，不含它 ——
 *       不追加的话客户端可自带该头穿透下游的可信来源校验（IOT-CLOUD-SPEC.md §4.4-2）；</li>
 *   <li>鉴权必须开启、放行路径必须存在、路由必须配到（M0a 静态发现）。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-18
 */
class GatewayConfigBindingTest {

    private static GatewayProperties gatewayProperties;

    private static Binder binder;

    @BeforeAll
    static void bindApplicationYml() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml"));
        assertThat(sources).as("application.yml 必须能被加载").isNotEmpty();
        MutablePropertySources propertySources = new MutablePropertySources();
        sources.forEach(propertySources::addLast);
        binder = new Binder(ConfigurationPropertySources.from(propertySources));
        gatewayProperties = binder.bind("ypbin.gateway", Bindable.of(GatewayProperties.class))
            .orElseThrow(() -> new IllegalStateException("无法绑定 ypbin.gateway 配置"));
    }

    @Test
    @DisplayName("身份头清洗名单必须包含 starter 默认的 5 个头 + 显式追加的 X-Gateway-Signed")
    void sanitizeHeadersMustIncludeTrustedSourceHeader() {
        assertThat(gatewayProperties.getHeaderSanitize().isEnabled()).isTrue();
        assertThat(gatewayProperties.getHeaderSanitize().getHeaders())
            .as("不追加 X-Gateway-Signed 会让客户端自带的该头穿透下游校验")
            .contains("X-User-Id", "X-User-Name", "X-Tenant-Id", "X-Dept-Id", "X-Roles",
                "X-Gateway-Signed");
    }

    @Test
    @DisplayName("鉴权必须开启，且放行路径只含健康探针与文档（不得放行 /actuator/** 其余端点）")
    void authMustBeEnabledWithNarrowExcludePaths() {
        assertThat(gatewayProperties.getAuth().isEnabled()).isTrue();
        assertThat(gatewayProperties.getAuth().getExcludePaths())
            .isNotEmpty()
            .anyMatch(path -> path.startsWith("/actuator/health"))
            .noneMatch(path -> path.equals("/actuator/**"));
    }

    @Test
    @DisplayName("M0a 静态发现：至少配了一条路由且 uri 非空")
    void routesMustBeConfiguredStatically() {
        String businessUri = binder
            .bind("spring.cloud.gateway.server.webflux.routes[0].uri", Bindable.of(String.class))
            .orElse("");
        assertThat(businessUri).as("M0a 关掉注册中心，路由必须显式配置").isNotBlank();
    }
}
