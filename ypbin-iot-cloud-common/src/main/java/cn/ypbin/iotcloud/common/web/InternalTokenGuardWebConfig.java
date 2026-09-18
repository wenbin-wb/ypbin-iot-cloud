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

import cn.ypbin.iotcloud.common.config.InternalProperties;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把 {@link InternalTokenGuardInterceptor} 注册到 Web MVC 拦截链，且<b>仅</b>拦截
 * {@code /internal/**}。
 *
 * <p>只作用于内部端点路径，不影响业务路径既有的鉴权与放行策略。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
public class InternalTokenGuardWebConfig implements WebMvcConfigurer {

    private final InternalProperties internalProperties;

    public InternalTokenGuardWebConfig(InternalProperties internalProperties) {
        this.internalProperties = internalProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new InternalTokenGuardInterceptor(internalProperties))
            .addPathPatterns("/internal/**");
    }
}
