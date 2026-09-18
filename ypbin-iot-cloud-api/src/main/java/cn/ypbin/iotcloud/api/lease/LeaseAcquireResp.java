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

import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 领取租约响应：本次分给该节点的租户及其租约到期时间。
 *
 * <p>同时返回各租户的 {@code epoch}，节点据此执行 §3.1② 的「先订阅、后拉取、按 epoch 准入」。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
@Getter
@Setter
public class LeaseAcquireResp {

    /** 节点标识。 */
    private String accessNode;

    /** 本次领取到的租户归属（空集合表示暂无分给该节点的租户，绝不为 null）。 */
    private List<LeaseAssignmentDto> assignments = List.of();
}
