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

import cn.ypbin.iotcloud.gateway.GatewayApplication;
import cn.ypbin.iotcloud.gateway.internal.InternalPathBlockFilter;
import cn.ypbin.starter.gateway.autoconfigure.GatewayAuthMissingProviderAutoConfiguration;
import cn.ypbin.starter.gateway.autoconfigure.GatewayAutoConfiguration;
import cn.ypbin.starter.gateway.filter.GatewayAuthGlobalFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

/**
 * 「网关必须自带认证器」的门禁。
 *
 * <p><b>为什么需要它</b>：starter 在 {@code ypbin.gateway.auth.enabled=true} 但容器里没有
 * {@link GatewayAuthProvider} 时，只打一条 WARN 并<b>不注册鉴权过滤器</b>（fail-open）。
 * 也就是说「配置看起来开着鉴权、实际全部放行」不会有任何构建失败。本测试把这个洞变成门禁：
 * 一旦有人删掉 Provider，它立刻转红。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
class GatewayAuthProviderPresenceTest {

    /**
     * <b>运行期</b>门禁：开启鉴权时，容器里必须真的注册出 {@link GatewayAuthGlobalFilter}。
     *
     * <p>为什么不按源码文本匹配 Provider：那只是代理断言。独立复核实测——给 Provider 加上
     * {@code @Profile("never-active")}（仍保留 {@code @Component}）后，文本门禁<b>照样全绿</b>，
     * 而运行期鉴权过滤器个数变成 0（fail-open 复活）。只有真的把上下文跑起来，才能守住这个洞。</p>
     */
    private final ReactiveWebApplicationContextRunner runner =
        new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GatewayAutoConfiguration.class,
                GatewayAuthMissingProviderAutoConfiguration.class))
            .withPropertyValues("ypbin.gateway.auth.enabled=true");

    @Test
    @DisplayName("提供 Provider 时，鉴权过滤器必须真的被注册（否则就是配了鉴权却全放行）")
    void authFilterMustBeRegisteredWhenProviderPresent() {
        runner.withUserConfiguration(PlatformGatewayAuthProvider.class)
            .run(context -> {
                assertThat(context).hasSingleBean(PlatformGatewayAuthProvider.class);
                assertThat(context).hasSingleBean(GatewayAuthGlobalFilter.class);
            });
    }

    @Test
    @DisplayName("以**真实应用类**为扫描根时，Provider 仍必须在扫描范围内（否则运行期照样 fail-open）")
    void providerMustBeVisibleFromApplicationScanRoot() {
        // 上一条用例显式注册了 Provider，覆盖不到「Provider 被挪出扫描范围 / 扫描根被改」这种形态：
        // 独立复核实测——把 @SpringBootApplication 的 scanBasePackages 改到别的包，显式注册版门禁仍全绿，
        // 而真进程启动会出现 starter 的 fail-open WARN、无令牌请求被直接转发到下游。
        // 因此这里直接用真实应用类当扫描根，让「扫描可见性」本身成为被断言的对象。
        new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GatewayAutoConfiguration.class,
                GatewayAuthMissingProviderAutoConfiguration.class))
            .withPropertyValues("ypbin.gateway.auth.enabled=true")
            .withUserConfiguration(GatewayApplication.class)
            .run(context -> {
                assertThat(context).hasSingleBean(PlatformGatewayAuthProvider.class);
                assertThat(context).hasSingleBean(GatewayAuthGlobalFilter.class);
            });
    }

    @Test
    @DisplayName("没有 Provider 时过滤器确实不被注册（这就是 fail-open，本仓靠上面那条门禁守住）")
    void authFilterIsNotRegisteredWithoutProvider() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(PlatformGatewayAuthProvider.class);
            assertThat(context).doesNotHaveBean(GatewayAuthGlobalFilter.class);
        });
    }

    @Test
    @DisplayName("真实扫描根下：内部端点封堵过滤器必须存在（缺了它 /business/internal/** 会穿到下游）")
    void internalPathBlockFilterMustBeVisibleFromApplicationScanRoot() {
        new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GatewayAutoConfiguration.class))
            .withUserConfiguration(GatewayApplication.class)
            .run(context -> assertThat(context)
                .as("内部端点封堵是网关侧的硬要求：路由会 StripPrefix 后把它改写成下游的 /internal/**")
                .hasSingleBean(InternalPathBlockFilter.class));
    }
}
