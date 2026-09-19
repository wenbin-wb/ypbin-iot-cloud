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
package cn.ypbin.iotcloud.business.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.ypbin.iotcloud.common.constant.InternalTokenConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 「容量缺省 = 不限」的端点用例（spec §3.1① 的「自用单节点可退化为一节点全量」）。
 *
 * <p>为什么单独一个类：本用例要求「可分配租户都还没被占用」，而 {@link InternalLeaseEndpointTest}
 * 与它共用上下文时归属存储会互相污染。这里用<b>另一组租户 + 独立属性</b>拿到干净的上下文
 * （Spring 的上下文缓存按配置区分，因此不会复用那边那份内存存储）。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@SpringBootTest(properties = {
    "ypbin.internal.token=" + InternalLeaseUnlimitedCapacityTest.TOKEN,
    "ypbin.lease.assignable-tenant-ids=33,44,55",
    "ypbin.lease.scan-interval-ms=3600000"
})
@AutoConfigureMockMvc
class InternalLeaseUnlimitedCapacityTest {

    /** 测试用内部凭证。 */
    static final String TOKEN = "test-internal-token-unlimited";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("注册时不传容量 → 领取到全部可分配租户（单节点全量模式）")
    void registerWithoutCapacityShouldBeUnlimited() throws Exception {
        mockMvc.perform(post(InternalLeaseController.BASE_PATH + "/register")
                .header(InternalTokenConstants.TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accessNode\":\"access-full\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post(InternalLeaseController.BASE_PATH + "/acquire")
                .header(InternalTokenConstants.TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accessNode\":\"access-full\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.assignments.length()").value(3));
    }
}
