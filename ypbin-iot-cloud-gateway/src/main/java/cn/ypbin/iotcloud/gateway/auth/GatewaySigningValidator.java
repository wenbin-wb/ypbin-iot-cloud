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
package cn.ypbin.iotcloud.gateway.auth;

import cn.ypbin.starter.gateway.autoconfigure.GatewayProperties;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 网关签发标记（{@code X-Gateway-Signed}）的启动期校验。
 *
 * <p><b>为什么需要它</b>：`trusted-source-token` 为空时网关<b>不签发</b>来源标记，而下游按
 * IOT-CLOUD-SPEC.md §4.4 会开启可信来源校验 —— 也就是说「网关没签发 + 下游要求校验」会让所有
 * 经网关的请求在下游被拒，而这条状态在启动日志里<b>完全没有痕迹</b>（禁静默降级红线）。</p>
 *
 * <p>M0a 骨架允许为空（尚无下游开启该校验），因此这里只做<b>显著告警</b>，不阻断启动；
 * M0b/P5 接真实部署时应把它升级为启动失败。判定逻辑抽成静态方法以便单测。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
@Component
@ConditionalOnProperty(prefix = "ypbin.gateway", name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class GatewaySigningValidator {

    private static final Logger log = LoggerFactory.getLogger(GatewaySigningValidator.class);

    private final GatewayProperties gatewayProperties;

    public GatewaySigningValidator(GatewayProperties gatewayProperties) {
        this.gatewayProperties = gatewayProperties;
    }

    /**
     * 判断当前配置是否存在「开启鉴权但未配置签发标记」的问题。
     *
     * @param properties 网关配置
     * @return 存在问题时返回可读原因
     */
    public static Optional<String> findProblem(GatewayProperties properties) {
        if (!properties.getAuth().isEnabled()) {
            return Optional.empty();
        }
        String token = properties.getAuth().getTrustedSourceToken();
        if (token == null || token.isBlank()) {
            return Optional.of("已开启网关鉴权，但 " + GatewayProperties.PREFIX
                + ".auth.trusted-source-token 未配置（或环境变量未注入）：网关不会签发 "
                + properties.getAuth().getTrustedSourceHeader()
                + " 标记，要求可信来源校验的下游会拒绝所有经网关的请求");
        }
        return Optional.empty();
    }

    /** 启动完成后校验并告警。 */
    @EventListener(ApplicationReadyEvent.class)
    public void warnIfSigningTokenMissing() {
        findProblem(gatewayProperties).ifPresent(problem ->
            log.error("[ypbin-iot-cloud-gateway] {}", problem));
    }
}
