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
package cn.ypbin.iotcloud.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 内部调用凭证配置。
 *
 * <p>服务间 Feign 直连访问 {@code /internal/**} 时须携带与配置一致的凭证头
 * （{@link cn.ypbin.iotcloud.common.constant.InternalTokenConstants#TOKEN_HEADER}）。
 * token 属密钥：只经环境变量/配置注入，<b>不落库、不进日志、不入 git</b>。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
@Getter
@Setter
@ConfigurationProperties(prefix = InternalProperties.PREFIX)
public class InternalProperties {

    /** 配置前缀。 */
    public static final String PREFIX = "ypbin.internal";

    /** 内部调用凭证（集群内各服务共享同一值）。 */
    private String token;
}
