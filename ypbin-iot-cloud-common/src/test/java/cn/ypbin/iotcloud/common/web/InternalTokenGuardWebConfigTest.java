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
package cn.ypbin.iotcloud.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iotcloud.common.config.InternalProperties;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

/**
 * 守卫注册路径测试。
 *
 * <p>只测「凭证对不对」会漏掉「拦错路径」：把 {@code /internal/**} 写成别的模式，
 * 守卫的所有行为用例都照样通过（独立复核实测：改成 {@code /internal-x/**} 后全绿）。
 * 因此这里直接断言注册进来的<b>路径模式</b>。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
class InternalTokenGuardWebConfigTest {

    /** 期望被拦截的路径模式（内部端点）。 */
    private static final String EXPECTED_PATTERN = "/internal/**";

    @Test
    @DisplayName("必须且只拦截 /internal/**")
    @SuppressWarnings("unchecked")
    void shouldRegisterExactlyInternalPaths() throws Exception {
        InterceptorRegistry registry = new InterceptorRegistry();
        new InternalTokenGuardWebConfig(new InternalProperties()).addInterceptors(registry);

        Field field = InterceptorRegistry.class.getDeclaredField("registrations");
        field.setAccessible(true);
        List<Object> registrations = (List<Object>) field.get(registry);
        assertThat(registrations).hasSize(1);

        Field patterns = registrations.get(0).getClass().getDeclaredField("includePatterns");
        patterns.setAccessible(true);
        assertThat((List<String>) patterns.get(registrations.get(0)))
            .containsExactly(EXPECTED_PATTERN);
    }
}
