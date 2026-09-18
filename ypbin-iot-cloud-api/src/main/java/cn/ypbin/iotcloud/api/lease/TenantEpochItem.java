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
package cn.ypbin.iotcloud.api.lease;

import lombok.Getter;
import lombok.Setter;

/**
 * 单个租户的台账版本号条目。
 *
 * @author wenbin
 * @since 2026-09-18
 */
@Getter
@Setter
public class TenantEpochItem {

    /** 租户 ID。 */
    private Long tenantId;

    /** 台账版本号。 */
    private Long epoch;
}
