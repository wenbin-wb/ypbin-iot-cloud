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
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 内部调用凭证配置的自动装配（<b>与 Web 类型无关</b>）。
 *
 * <p>把 {@link InternalProperties} 的注册单独拆出来，是因为它不只服务于入站守卫：
 * 出站侧（{@code LeaseFeignConfiguration} 的凭证头拦截器）同样需要这个 bean。
 * 若把它绑在「仅 Servlet」的守卫装配里，<b>非 Servlet 宿主或尚未装配守卫的宿主</b>在创建 Feign
 * 子上下文时会因缺少该 bean 而启动失败。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
@AutoConfiguration
@EnableConfigurationProperties(InternalProperties.class)
public class InternalTokenAutoConfiguration {
}
