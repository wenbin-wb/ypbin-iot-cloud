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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 释放租约请求：节点正常下线时归还租户（异常宕机走「到期 + 接管」路径）。
 *
 * @author wenbin
 * @since 2026-09-18
 */
@Getter
@Setter
public class LeaseReleaseReq {

    /** 节点唯一标识。 */
    @NotBlank(message = "节点标识不能为空")
    private String accessNode;

    /** 要释放的租户 ID（空集合表示释放该节点全部租户，绝不为 null）。 */
    @NotEmpty(message = "释放的租户不能为空")
    private List<Long> tenantIds;
}
