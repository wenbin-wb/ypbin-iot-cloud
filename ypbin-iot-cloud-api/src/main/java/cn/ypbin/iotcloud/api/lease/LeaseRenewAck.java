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
 * 单个租户的续约回执。
 *
 * <p><b>为什么必须逐租户回执</b>：一个节点可同时持有多个租户，各租户的到期时间并不相同。
 * 若响应只给一个「下次到期时间」，节点就无法更新本地的逐租户 {@code leaseExpireAt}，
 * 也就无法在续约之后继续用本地时间判断自己是否已经过期（IOT-CLOUD-SPEC.md §3.1① 的依据①）。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
@Getter
@Setter
public class LeaseRenewAck {

    /** 租户 ID。 */
    private Long tenantId;

    /** 该租户续约后的新到期时间。 */
    private LocalDateTime leaseExpireAt;

    /** 该租户当前的台账版本号（节点据此判断自己是否落后）。 */
    private Long epoch;
}
