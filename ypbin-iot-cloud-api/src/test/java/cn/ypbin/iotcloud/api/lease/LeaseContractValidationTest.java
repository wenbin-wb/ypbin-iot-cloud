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

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 租约契约的参数校验测试：契约字段的必填/正数约束必须真的被 Bean Validation 拦截，
 * 否则「过期时间必填」这类约定只写在文档里。
 *
 * @author wenbin
 * @since 2026-09-18
 */
class LeaseContractValidationTest {

    private static ValidatorFactory factory;

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    @DisplayName("注册请求缺少节点标识时必须校验失败")
    void registerShouldRequireNodeId() {
        assertThat(validator.validate(new AccessNodeRegisterReq())).hasSize(1);
    }

    @Test
    @DisplayName("续约请求必须带节点标识与非空租约条目")
    void renewShouldRequireNodeIdAndLeases() {
        LeaseRenewReq req = new LeaseRenewReq();
        assertThat(validator.validate(req)).hasSize(2);

        req.setAccessNode("access-1");
        req.setLeases(List.of());
        assertThat(validator.validate(req)).hasSize(1);
    }

    @Test
    @DisplayName("续约条目必须带租户 ID 与版本号，且租户 ID 为正数")
    void renewItemShouldRequirePositiveTenantIdAndEpoch() {
        LeaseRenewItem item = new LeaseRenewItem();
        assertThat(validator.validate(item)).hasSize(2);

        item.setTenantId(-1L);
        item.setEpoch(1L);
        assertThat(validator.validate(item)).hasSize(1);

        item.setTenantId(1L);
        assertThat(validator.validate(item)).isEmpty();
    }

    @Test
    @DisplayName("释放请求必须带节点标识与租户列表")
    void releaseShouldRequireNodeIdAndTenantIds() {
        LeaseReleaseReq req = new LeaseReleaseReq();
        assertThat(validator.validate(req)).hasSize(2);
    }
}
