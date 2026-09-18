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

import cn.ypbin.iotcloud.common.config.InternalProperties;
import cn.ypbin.iotcloud.common.constant.InternalTokenConstants;
import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * Feign 出站拦截器：为内部调用自动携带内部凭证头。
 *
 * <p>与入站守卫（{@code InternalTokenGuardInterceptor}）配对：一侧加头、一侧校验，
 * 两侧共用 {@link InternalTokenConstants} 的常量，避免字符串各写一份而不一致。</p>
 *
 * <p>凭证未配置时<b>不加头</b>（而不是加空头）：请求随后会被对端守卫以 fail-closed 拒绝，
 * 失败原因指向配置缺失，而不是一条难以定位的「凭证不匹配」。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
public class InternalTokenRequestInterceptor implements RequestInterceptor {

    private final InternalProperties internalProperties;

    public InternalTokenRequestInterceptor(InternalProperties internalProperties) {
        this.internalProperties = internalProperties;
    }

    @Override
    public void apply(RequestTemplate template) {
        String token = internalProperties.getToken();
        if (token != null && !token.isBlank()) {
            template.header(InternalTokenConstants.TOKEN_HEADER, token);
        }
    }
}
