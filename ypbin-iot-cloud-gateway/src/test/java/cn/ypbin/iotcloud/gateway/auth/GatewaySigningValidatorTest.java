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

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.starter.gateway.autoconfigure.GatewayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 签发标记缺失的判定测试：**必须能被发现**，否则「网关不签发 + 下游要求校验」会让所有请求在下游被静默拒绝。
 *
 * @author wenbin
 * @since 2026-09-18
 */
class GatewaySigningValidatorTest {

    private GatewayProperties propertiesWithAuthEnabled(boolean enabled, String token) {
        GatewayProperties properties = new GatewayProperties();
        properties.getAuth().setEnabled(enabled);
        properties.getAuth().setTrustedSourceToken(token);
        return properties;
    }

    @Test
    @DisplayName("开启鉴权但签发标记为空 → 必须报出问题（含配置键，便于定位）")
    void blankTokenWithAuthEnabledMustBeReported() {
        assertThat(GatewaySigningValidator.findProblem(propertiesWithAuthEnabled(true, null)))
            .isPresent()
            .get().asString().contains("trusted-source-token");
        assertThat(GatewaySigningValidator.findProblem(propertiesWithAuthEnabled(true, "   ")))
            .isPresent();
    }

    @Test
    @DisplayName("启动告警方法必须可调用且不抛异常（缺失时报错、正常时静默）")
    void startupWarningMustBeSafeToCall() {
        GatewayProperties missing = propertiesWithAuthEnabled(true, null);
        new GatewaySigningValidator(missing).warnIfSigningTokenMissing();

        GatewayProperties configured = propertiesWithAuthEnabled(true, "s3cret");
        new GatewaySigningValidator(configured).warnIfSigningTokenMissing();
    }

    @Test
    @DisplayName("配置了签发标记、或未开启鉴权 → 不报问题")
    void noProblemWhenTokenConfiguredOrAuthDisabled() {
        assertThat(GatewaySigningValidator.findProblem(propertiesWithAuthEnabled(true, "s3cret")))
            .isEmpty();
        assertThat(GatewaySigningValidator.findProblem(propertiesWithAuthEnabled(false, null)))
            .isEmpty();
    }
}
