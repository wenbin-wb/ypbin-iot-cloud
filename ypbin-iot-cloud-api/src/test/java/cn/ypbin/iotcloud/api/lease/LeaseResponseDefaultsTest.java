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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 租约响应的集合字段默认值测试。
 *
 * <p>铁律「返回集合永不 null」对契约 DTO 同样成立：调用方（access）会直接对响应里的集合做
 * 遍历/流式处理，一旦为 null 就是 NPE。这里把「默认为空集合」变成可执行断言，而不是文档约定。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
class LeaseResponseDefaultsTest {

    @Test
    @DisplayName("领取响应默认返回空集合而非 null")
    void acquireRespDefaultsToEmptyList() {
        assertThat(new LeaseAcquireResp().getAssignments()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("续约响应默认返回空集合而非 null（revoked 为空表示无需 fencing）")
    void renewRespDefaultsToEmptyLists() {
        LeaseRenewResp resp = new LeaseRenewResp();
        assertThat(resp.getRenewedTenantIds()).isNotNull().isEmpty();
        assertThat(resp.getRevokedTenantIds()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("批量版本号响应默认返回空集合而非 null")
    void epochBatchRespDefaultsToEmptyList() {
        assertThat(new TenantEpochBatchResp().getItems()).isNotNull().isEmpty();
    }
}
