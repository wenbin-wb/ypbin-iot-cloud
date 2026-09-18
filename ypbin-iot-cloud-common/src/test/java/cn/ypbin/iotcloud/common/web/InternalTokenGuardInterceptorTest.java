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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.iotcloud.common.config.InternalProperties;
import cn.ypbin.iotcloud.common.constant.InternalTokenConstants;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 入站守卫三态测试：未配置凭证 / 凭证错误 / 凭证正确。
 *
 * <p>三态缺一不可：只测「正确放行」会让 fail-open 与校验形同虚设的实现也通过。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
class InternalTokenGuardInterceptorTest {

    private static final String TOKEN = "test-internal-token";

    private InternalTokenGuardInterceptor interceptorWith(String token) {
        InternalProperties properties = new InternalProperties();
        properties.setToken(token);
        return new InternalTokenGuardInterceptor(properties);
    }

    private MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/lease/assignments");
        if (token != null) {
            request.addHeader(InternalTokenConstants.TOKEN_HEADER, token);
        }
        return request;
    }

    @Test
    @DisplayName("凭证未配置时必须整体拒绝（fail-closed），且错误码为 401")
    void shouldRejectWhenTokenNotConfigured() {
        assertThatThrownBy(() -> interceptorWith(null)
            .preHandle(requestWithToken(TOKEN), new MockHttpServletResponse(), new Object()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getCode())
            .isEqualTo(GlobalErrorCode.UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("凭证为空串同样按未配置处理（防「配了但为空」的静默放行）")
    void shouldRejectWhenTokenBlank() {
        assertThatThrownBy(() -> interceptorWith("   ")
            .preHandle(requestWithToken(TOKEN), new MockHttpServletResponse(), new Object()))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("凭证错误或缺失时拒绝，且错误码为 401")
    void shouldRejectWhenTokenMismatch() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> interceptorWith(TOKEN)
            .preHandle(requestWithToken("wrong-token"), response, new Object()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getCode())
            .isEqualTo(GlobalErrorCode.UNAUTHORIZED.getCode());

        assertThatThrownBy(() -> interceptorWith(TOKEN)
            .preHandle(requestWithToken(null), new MockHttpServletResponse(), new Object()))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("凭证一致时放行")
    void shouldPassWhenTokenMatches() {
        assertThat(interceptorWith(TOKEN)
            .preHandle(requestWithToken(TOKEN), new MockHttpServletResponse(), new Object()))
            .isTrue();
    }
}
