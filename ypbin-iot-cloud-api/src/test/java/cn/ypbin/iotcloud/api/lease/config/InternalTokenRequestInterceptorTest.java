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
import cn.ypbin.iotcloud.common.constant.InternalTokenConstants;
import feign.RequestTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 出站凭证头拦截器测试：配了凭证必须加头，没配必须<b>不加空头</b>。
 *
 * @author wenbin
 * @since 2026-09-18
 */
class InternalTokenRequestInterceptorTest {

    private RequestTemplate applyWith(String token) {
        InternalProperties properties = new InternalProperties();
        properties.setToken(token);
        RequestTemplate template = new RequestTemplate();
        new InternalTokenRequestInterceptor(properties).apply(template);
        return template;
    }

    @Test
    @DisplayName("凭证已配置时携带内部凭证头")
    void shouldAddHeaderWhenConfigured() {
        assertThat(applyWith("token-abc").headers().get(InternalTokenConstants.TOKEN_HEADER))
            .containsExactly("token-abc");
    }

    @Test
    @DisplayName("凭证未配置或为空时不加头（交由对端 fail-closed 拒绝，失败点更明确）")
    void shouldNotAddHeaderWhenAbsent() {
        assertThat(applyWith(null).headers()).doesNotContainKey(InternalTokenConstants.TOKEN_HEADER);
        assertThat(applyWith("  ").headers()).doesNotContainKey(InternalTokenConstants.TOKEN_HEADER);
    }
}
