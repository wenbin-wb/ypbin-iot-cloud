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

import cn.ypbin.iotcloud.common.config.InternalProperties;
import cn.ypbin.iotcloud.common.web.InternalTokenGuardWebConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 内部端点守卫的自动装配。
 *
 * <p>只在<b>Servlet 应用</b>且类路径存在 Spring MVC 时装配——本仓的 `gateway` 是 WebFlux
 * （响应式）应用，若把 MVC 相关配置强行装配进去会让 Boot 判定为 Servlet 应用并破坏网关。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
@AutoConfiguration
@ConditionalOnClass({DispatcherServlet.class, WebMvcConfigurer.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class InternalGuardAutoConfiguration {

    /**
     * 注册内部端点守卫（仅拦截 {@code /internal/**}）。
     *
     * @param internalProperties 内部调用凭证配置
     * @return MVC 配置
     */
    @Bean
    @ConditionalOnMissingBean
    public InternalTokenGuardWebConfig internalTokenGuardWebConfig(
            InternalProperties internalProperties) {
        return new InternalTokenGuardWebConfig(internalProperties);
    }
}
