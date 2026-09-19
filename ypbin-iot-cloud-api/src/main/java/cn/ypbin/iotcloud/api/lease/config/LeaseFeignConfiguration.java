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
import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 租约 Feign 客户端的公共配置：<b>显式超时</b> + 内部凭证头。
 *
 * <p>超时取值不是随手填的：续约周期默认 <b>10s</b>（§3.1①），读超时必须<b>远小于</b>该周期，
 * 否则一次卡住就会让续约跨过到期时间、把自己「卡成」失效节点；同时又要容纳正常的
 * 内部写库耗时。取 connect=1s / read=3s。</p>
 *
 * <p>注意：本类<b>不</b>标注 {@code @AutoConfiguration}——它是通过
 * {@code @FeignClient(configuration = ...)} 挂到该客户端上的局部配置，不参与全局自动装配。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
@Configuration(proxyBeanMethods = false)
public class LeaseFeignConfiguration {

    /** 连接超时（毫秒）。 */
    public static final int CONNECT_TIMEOUT_MS = 1000;

    /** 读超时（毫秒）：必须远小于续约周期 10s，避免续约被卡过到期时间。 */
    public static final int READ_TIMEOUT_MS = 3000;

    /**
     * 租约客户端的显式超时配置。
     *
     * @return Feign 请求选项
     */
    @Bean
    @ConditionalOnMissingBean
    public Request.Options leaseRequestOptions() {
        return new Request.Options(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS,
            READ_TIMEOUT_MS, TimeUnit.MILLISECONDS, true);
    }

    /**
     * 内部凭证头拦截器。
     *
     * @param internalProperties 内部调用凭证配置
     * @return 出站拦截器
     */
    @Bean
    @ConditionalOnMissingBean
    public RequestInterceptor internalTokenRequestInterceptor(InternalProperties internalProperties) {
        return new InternalTokenRequestInterceptor(internalProperties);
    }

    /**
     * <b>显式禁止自动重试</b>。
     *
     * <p>不声明它时，Feign 会用默认 {@code Retryer.Default}（maxAttempts=5、period=100ms、maxPeriod=1s）：
     * 单次续约最坏 ≈ 5×(1s connect + 3s read) + 退避 ≈ <b>21.5s</b>，已经<b>超过 10s 续约周期</b>——
     * 正是「一次抖动就把自己卡成失效节点、被接管」的形态；而只看读超时的断言发现不了它。</p>
     *
     * <p>这里取「不重试」：续约是<b>周期性</b>调用，下一轮（≤10s）本身就会重发，重试只会叠加延迟。
     * 真正需要重试的场景由调用方在上层做<b>有界</b>重试，并把「续约连续失败」当作 self-fencing 的触发条件之一。</p>
     *
     * @return 永不重试的策略
     */
    @Bean
    @ConditionalOnMissingBean
    public Retryer leaseRetryer() {
        return Retryer.NEVER_RETRY;
    }

}
