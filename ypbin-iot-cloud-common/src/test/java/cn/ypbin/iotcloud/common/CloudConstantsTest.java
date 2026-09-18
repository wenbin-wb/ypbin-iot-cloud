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
package cn.ypbin.iotcloud.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CloudConstants} 的占位用例。
 *
 * <p>它同时承担一个门禁职责：{@code common} 是 M0a 唯一有主源码的库模块，
 * 若没有任何用例执行它，JaCoCo 的指令覆盖率为 0，覆盖率门禁（指令 >= 0.30）必红。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
class CloudConstantsTest {

    @Test
    @DisplayName("模块清单与 IOT-CLOUD-SPEC.md §2 / §4.2 一致，且库模块与部署单元不重叠")
    void moduleListsShouldStayInSyncWithSpec() {
        assertThat(CloudConstants.ARTIFACT_PREFIX).isEqualTo("ypbin-iot-cloud-");
        assertThat(CloudConstants.LIBRARY_MODULES)
                .hasSize(5)
                .containsExactly("common", "api", "auth", "core", "openapi");
        assertThat(CloudConstants.DEPLOYMENT_UNITS)
                .hasSize(3)
                .containsExactly("gateway", "business", "access");
        assertThat(List.copyOf(CloudConstants.LIBRARY_MODULES))
                .doesNotContainAnyElementsOf(CloudConstants.DEPLOYMENT_UNITS);
    }
}
