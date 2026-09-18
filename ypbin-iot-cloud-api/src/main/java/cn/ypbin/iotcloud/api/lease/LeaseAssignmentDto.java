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

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 单个租户的归属视图（契约 DTO，<b>不暴露持久化实体</b>）。
 *
 * @author wenbin
 * @since 2026-09-18
 */
@Getter
@Setter
public class LeaseAssignmentDto {

    /** 租户 ID。 */
    private Long tenantId;

    /** 当前归属的 access 节点标识。 */
    private String accessNode;

    /** 租约到期时间（节点须在此之前续约，否则可被判定失效并接管）。 */
    private LocalDateTime leaseExpireAt;

    /** 台账版本号（变更与增号必须同事务，见 §3.1③）。 */
    private Long epoch;

    /** 租约状态。 */
    private LeaseState state;
}
