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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * {@code /internal/lease/**} 的端到端测试（真启动 business 上下文，不打桩）。
 *
 * <p>这条用例同时守住四件事，任何一件坏了都会红：</p>
 * <ol>
 *   <li><b>入站守卫真的挂上了</b>：不带 {@code X-Internal-Token} → HTTP 200 + {@code R.code=401}
 *       （守卫由 common 的自动配置提供，business 只加了依赖——所以这条同时验证了依赖与装配）；</li>
 *   <li><b>统一信封成立</b>：异常被转成 HTTP 200 而不是 4xx/5xx（依赖 {@code ypbin-starter-web}）；</li>
 *   <li><b>租约契约路径逐字对得上</b>：路径与 {@code ILeaseClient} 的 Feign 声明一致，否则 404；</li>
 *   <li><b>核心语义在真上下文里也成立</b>：注册 → 领取 → 续约 → 释放 → 再分配。</li>
 * </ol>
 *
 * <p>扫描周期在测试里调到 1 小时：否则 15s 的定时扫描会在用例中途把租约置为待接管，造成偶发红。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@SpringBootTest(properties = {
    "ypbin.internal.token=" + InternalLeaseEndpointTest.TOKEN,
    "ypbin.lease.assignable-tenant-ids=11,22",
    "ypbin.lease.scan-interval-ms=3600000"
})
@AutoConfigureMockMvc
class InternalLeaseEndpointTest {

    /** 测试用内部凭证（与 application.yml 里的占位无关）。 */
    static final String TOKEN = "test-internal-token";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("不带内部凭证 → HTTP 200 + R.code=401（fail-closed，且不能是 4xx/5xx）")
    void requestWithoutTokenMustBeRejectedWithEnvelope() throws Exception {
        mockMvc.perform(post(InternalLeaseController.BASE_PATH + "/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accessNode\":\"access-1\",\"maxTenants\":2}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("凭证错误 → 同样是 HTTP 200 + R.code=401")
    void requestWithWrongTokenMustBeRejected() throws Exception {
        mockMvc.perform(get(InternalLeaseController.BASE_PATH + "/epochs")
                .header(InternalTokenConstants.TOKEN_HEADER, "wrong-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("带凭证：注册 → 领取 → 续约 → 释放 → 另一节点重新分配（epoch 不因释放而变）")
    void leaseLifecycleShouldWorkThroughHttp() throws Exception {
        register("access-1", 2);

        mockMvc.perform(post(InternalLeaseController.BASE_PATH + "/acquire")
                .header(InternalTokenConstants.TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accessNode\":\"access-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.accessNode").value("access-1"))
            .andExpect(jsonPath("$.data.assignments.length()").value(2))
            .andExpect(jsonPath("$.data.assignments[0].state").value("ACTIVE"));

        mockMvc.perform(post(InternalLeaseController.BASE_PATH + "/renew")
                .header(InternalTokenConstants.TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accessNode\":\"access-1\",\"leases\":[{\"tenantId\":11,\"epoch\":1}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.renewedLeases.length()").value(1))
            .andExpect(jsonPath("$.data.revokedTenantIds.length()").value(0))
            .andExpect(jsonPath("$.data.nodeFenced").value(false));

        mockMvc.perform(post(InternalLeaseController.BASE_PATH + "/release")
                .header(InternalTokenConstants.TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accessNode\":\"access-1\",\"tenantIds\":[11]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        register("access-2", 1);
        mockMvc.perform(post(InternalLeaseController.BASE_PATH + "/acquire")
                .header(InternalTokenConstants.TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accessNode\":\"access-2\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.assignments[0].tenantId").value(11))
            .andExpect(jsonPath("$.data.assignments[0].accessNode").value("access-2"));
    }

    @Test
    @DisplayName("未注册的节点续约 → nodeFenced=true（节点必须整体停采后重新注册）")
    void renewFromUnregisteredNodeMustBeFenced() throws Exception {
        mockMvc.perform(post(InternalLeaseController.BASE_PATH + "/renew")
                .header(InternalTokenConstants.TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accessNode\":\"ghost\",\"leases\":[{\"tenantId\":11,\"epoch\":1}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.nodeFenced").value(true))
            .andExpect(jsonPath("$.data.revokedTenantIds[0]").value(11));
    }

    @Test
    @DisplayName("对账：批量 epoch 覆盖配置里的租户")
    void batchEpochShouldListConfiguredTenants() throws Exception {
        mockMvc.perform(get(InternalLeaseController.BASE_PATH + "/epochs")
                .header(InternalTokenConstants.TOKEN_HEADER, TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.readAt").exists());
    }

    @Test
    @DisplayName("从未分配过的租户 → data 为空（语义是「当前无归属」），不是报错")
    void assignmentOfUnknownTenantShouldReturnEmptyData() throws Exception {
        mockMvc.perform(post(InternalLeaseController.BASE_PATH + "/assignment")
                .header(InternalTokenConstants.TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":999}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("参数校验生效：容量为 0 → HTTP 200 + R.code=400（而不是 500）")
    void invalidRequestMustFailWithValidationEnvelope() throws Exception {
        mockMvc.perform(post(InternalLeaseController.BASE_PATH + "/register")
                .header(InternalTokenConstants.TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accessNode\":\"access-1\",\"maxTenants\":0}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));
    }

    private void register(String accessNode, int maxTenants) throws Exception {
        mockMvc.perform(post(InternalLeaseController.BASE_PATH + "/register")
                .header(InternalTokenConstants.TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accessNode\":\"" + accessNode + "\",\"maxTenants\":" + maxTenants + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }
}
