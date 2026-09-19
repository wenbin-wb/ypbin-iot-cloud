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
package cn.ypbin.iotcloud.common.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iotcloud.common.config.InternalProperties;
import cn.ypbin.iotcloud.common.web.InternalTokenGuardWebConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * 入站守卫自动装配测试。
 *
 * <p>重点在两条<b>边界</b>：① Servlet 应用必须装配；② <b>反应式（WebFlux）应用绝不能装配</b>——
 * 本仓 gateway 是 WebFlux 应用，一旦把 MVC 拦截器装配进去，Boot 会判定为 Servlet 应用并破坏网关。
 * 只测「Servlet 能装配」会漏掉后者。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
class InternalGuardAutoConfigurationTest {

    /** 与 imports 文件的实际登记一致：凭证配置（与 Web 类型无关）+ 守卫（仅 Servlet）。 */
    private final WebApplicationContextRunner servletRunner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(InternalTokenAutoConfiguration.class,
            InternalGuardAutoConfiguration.class));

    private final ReactiveWebApplicationContextRunner reactiveRunner =
        new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(InternalTokenAutoConfiguration.class,
                InternalGuardAutoConfiguration.class));

    @Test
    @DisplayName("Servlet 应用应装配入站守卫，并绑定内部凭证配置")
    void shouldConfigureGuardInServletApplication() {
        servletRunner.run(context -> {
            assertThat(context).hasSingleBean(InternalTokenGuardWebConfig.class);
            assertThat(context).hasSingleBean(InternalProperties.class);
        });
    }

    @Test
    @DisplayName("反应式（WebFlux）应用不得装配入站守卫（否则会把网关判成 Servlet 应用）")
    void shouldNotConfigureGuardInReactiveApplication() {
        reactiveRunner.run(context -> {
            assertThat(context).doesNotHaveBean(InternalTokenGuardWebConfig.class);
            // 但凭证配置必须仍在：出站 Feign 拦截器依赖它，缺了会让 Feign 子上下文启动失败
            assertThat(context).hasSingleBean(InternalProperties.class);
        });
    }

    @Test
    @DisplayName("已存在自定义守卫配置时不重复装配（@ConditionalOnMissingBean 语义）")
    void shouldBackOffWhenUserProvidesOwnConfig() {
        servletRunner
            .withBean(InternalTokenGuardWebConfig.class,
                () -> new InternalTokenGuardWebConfig(new InternalProperties()))
            .run(context -> assertThat(context).hasSingleBean(InternalTokenGuardWebConfig.class));
    }
}
