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
package cn.ypbin.iotcloud.core.autoconfigure;

import cn.ypbin.iotcloud.core.lease.LeaseProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 租约参数的注册（<b>无条件</b>）。
 *
 * <p>为什么必须与 {@link LeaseAutoConfiguration} 拆开：那个类带类级
 * {@code @ConditionalOnProperty(ypbin.lease.enabled)}，一旦把
 * {@code @EnableConfigurationProperties} 也放在它上面，{@code enabled=false} 时
 * {@link LeaseProperties} 这个 bean 会**一起消失**——任何注入它的组件（例如 business 的启动自检）
 * 就会让应用**启动失败**。也就是说「关掉租约维护」会变成「把服务弄挂」，与
 * {@link LeaseProperties#isEnabled()} 承诺的「本地只想跑业务接口时用」正好相反
 * （独立复核实测：升级后 `--ypbin.lease.enabled=false` 直接 APPLICATION FAILED TO START）。</p>
 *
 * <p>本仓 P1 对 {@code InternalProperties} 用过完全相同的拆法
 * （{@code InternalTokenAutoConfiguration} 只有属性、条件放在守卫装配上），这里保持一致：
 * <b>属性 bean 恒在，只有租约维护的组件受开关约束。</b></p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@AutoConfiguration
@EnableConfigurationProperties(LeaseProperties.class)
public class LeasePropertiesAutoConfiguration {
}
