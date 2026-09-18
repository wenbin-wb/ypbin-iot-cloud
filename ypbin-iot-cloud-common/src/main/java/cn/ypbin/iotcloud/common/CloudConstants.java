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

import java.util.List;

/**
 * 骨架期占位常量类：只登记本仓的模块清单，不含任何业务语义。
 *
 * <p>存在的理由有两个：</p>
 * <ol>
 *   <li>让 {@code common} 在 M0a 就有可被覆盖率门禁度量的类 —— 只有 package-info 的模块
 *       不产生任何指令，JaCoCo 会按「比值为 NaN」跳过门禁，等于门禁空转；</li>
 *   <li>为后续「模块清单不得漂移」的门禁提供单一事实源（M0b 的覆盖率快照 / SBOM 门禁会用到）。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-18
 */
public final class CloudConstants {

    /** Maven 坐标前缀（groupId 固定 cn.ypbin，artifactId 前缀见 name）。 */
    public static final String ARTIFACT_PREFIX = "ypbin-iot-cloud-";

    /** 5 个库模块（IOT-CLOUD-SPEC.md §2）。 */
    public static final List<String> LIBRARY_MODULES = List.of("common", "api", "auth", "core", "openapi");

    /** 3 个部署单元（IOT-CLOUD-SPEC.md §4.2）。 */
    public static final List<String> DEPLOYMENT_UNITS = List.of("gateway", "business", "access");

    private CloudConstants() {
    }
}
