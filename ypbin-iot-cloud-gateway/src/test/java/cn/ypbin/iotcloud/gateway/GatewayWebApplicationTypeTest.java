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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;

/**
 * 网关的 Web 应用类型门禁：类路径必须解析为 <b>REACTIVE</b>。
 *
 * <p>为什么需要它：Spring Boot 在「同时存在 spring-webmvc 与 spring-webflux」时会判定为
 * <b>SERVLET</b>（实测 Boot 4.1.1：两种 jar 顺序都一样），网关会因缺少响应式运行时而起不来。
 * 而入站守卫的单元测试只看得见 common 自己，<b>看不见网关的解析依赖</b>——
 * 一旦有人把 common 的 servlet 依赖从 {@code optional} 改成传递依赖，或让网关间接依赖含
 * servlet 栈的模块，只有本用例会转红。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
class GatewayWebApplicationTypeTest {

    @Test
    @DisplayName("gateway 的类路径必须解析为 REACTIVE（不得混入 servlet 栈）")
    void classpathMustResolveToReactive() {
        assertThat(WebApplicationType.deduce())
            .as("混入 spring-webmvc / tomcat 会让 Boot 把网关判成 SERVLET 应用")
            .isEqualTo(WebApplicationType.REACTIVE);
    }
}
