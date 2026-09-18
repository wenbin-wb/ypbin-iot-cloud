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
 * 批量租户版本号响应。
 *
 * <p>周期对账<b>必须一次拉全量</b>（不是每租户一次 Feign，见 §3.1③），且判据<b>只用 epoch</b>：
 * 设备数在「改参数」或「删一台又加一台」时不变，用它做判据会产生假阴性。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
@Getter
@Setter
public class TenantEpochBatchResp {

    /** 全部租户的版本号（空集合表示暂无租户，绝不为 null）。 */
    private List<TenantEpochItem> items = List.of();
}
