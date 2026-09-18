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

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 续约请求：节点周期上报自己仍持有的租约（默认 10s 一次）。
 *
 * @author wenbin
 * @since 2026-09-18
 */
@Getter
@Setter
public class LeaseRenewReq {

    /** 节点唯一标识。 */
    @NotBlank(message = "节点标识不能为空")
    private String accessNode;

    /** 本节点当前持有的租约（空集合表示本节点已无租约，属合法情况）。 */
    @NotEmpty(message = "续约条目不能为空")
    @Valid
    private List<LeaseRenewItem> leases;
}
