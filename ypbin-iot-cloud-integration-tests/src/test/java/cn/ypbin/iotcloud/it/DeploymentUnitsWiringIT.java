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
package cn.ypbin.iotcloud.it;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iotcloud.access.AccessApplication;
import cn.ypbin.iotcloud.business.BusinessApplication;
import cn.ypbin.iotcloud.gateway.GatewayApplication;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 集成测试占位用例（M0a）。
 *
 * <p>存在的意义是让 {@code -Pit} 的 failsafe 接线可被验证：没有它，
 * 「-Pit 跑了 0 个 IT」与「-Pit 没跑」在日志上完全一样（门禁空转的经典形态）。
 * 真正的端到端场景（三单元互调、租户上下文贯通、入站校验负例、租约失效）在 P5 落地。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
class DeploymentUnitsWiringIT {

    @Test
    @DisplayName("三个部署单元的启动类必须在集成测试 classpath 上且带 Boot 注解")
    void deploymentUnitsShouldBeWired() {
        List<Class<?>> applicationTypes = List.of(
                GatewayApplication.class, BusinessApplication.class, AccessApplication.class);
        assertThat(applicationTypes).hasSize(3);
        for (Class<?> applicationType : applicationTypes) {
            assertThat(applicationType.isAnnotationPresent(SpringBootApplication.class))
                    .as("%s 缺少 @SpringBootApplication", applicationType.getName())
                    .isTrue();
        }
    }
}
