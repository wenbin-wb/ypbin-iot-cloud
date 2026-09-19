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
package cn.ypbin.iotcloud.business.startup;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iotcloud.common.config.InternalProperties;
import cn.ypbin.iotcloud.core.lease.LeaseProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 启动自检测试。
 *
 * <p>覆盖两类「服务能起来但线上会出问题」的配置错误，以及正常配置下必须**安静**（不制造噪音日志）。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
class BusinessStartupCheckerTest {

    private final InternalProperties internal = new InternalProperties();
    private final LeaseProperties lease = new LeaseProperties();

    @Test
    @DisplayName("正常配置：无错误、无警告")
    void shouldBeQuietWithSaneConfiguration() {
        internal.setToken("shared-secret");
        lease.setAssignableTenantIds(List.of(11L));

        BusinessStartupChecker checker = new BusinessStartupChecker(internal, lease);

        assertThat(checker.errors()).isEmpty();
        assertThat(checker.warnings()).isEmpty();
    }

    @Test
    @DisplayName("租约有效期不大于续约周期 → 报错误（抖动即误判失效）")
    void shouldReportErrorWhenTtlTooShort() {
        lease.setTtl(Duration.ofSeconds(5));
        lease.setExpectedRenewInterval(Duration.ofSeconds(10));

        BusinessStartupChecker checker = new BusinessStartupChecker(internal, lease);

        assertThat(checker.errors()).singleElement().asString().contains("不大于预期续约周期");
    }

    @Test
    @DisplayName("未配内部凭证 / 可分配租户为空 → 各一条警告")
    void shouldWarnAboutMissingTokenAndTenants() {
        BusinessStartupChecker checker = new BusinessStartupChecker(internal, lease);

        assertThat(checker.warnings()).hasSize(2);
        assertThat(checker.warnings().get(0)).contains("ypbin.internal.token");
        assertThat(checker.warnings().get(1)).contains("assignable-tenant-ids");
    }

    @Test
    @DisplayName("边界：ttl 恰好等于续约周期也算不合法（判定必须是 <=，不是 <）")
    void shouldReportErrorWhenTtlEqualsInterval() {
        lease.setTtl(Duration.ofSeconds(10));
        lease.setExpectedRenewInterval(Duration.ofSeconds(10));

        BusinessStartupChecker checker = new BusinessStartupChecker(internal, lease);

        assertThat(checker.errors()).singleElement().asString().contains("不大于预期续约周期");
    }

    @Test
    @DisplayName("参数自身不合法：续约周期为 0 → 报错（否则「有效期是否足够」会被静默绕过）")
    void shouldReportErrorWhenIntervalNotPositive() {
        lease.setExpectedRenewInterval(Duration.ZERO);

        BusinessStartupChecker checker = new BusinessStartupChecker(internal, lease);

        assertThat(checker.errors()).singleElement().asString().contains("expected-renew-interval 必须为正数");
    }

    @Test
    @DisplayName("参数自身不合法：ttl 为 0 → 报错")
    void shouldReportErrorWhenTtlNotPositive() {
        lease.setTtl(Duration.ZERO);

        BusinessStartupChecker checker = new BusinessStartupChecker(internal, lease);

        assertThat(checker.errors()).singleElement().asString().contains("ttl 必须为正数");
    }

    @Test
    @DisplayName("关掉租约维护时不做租约相关检查（组件都没装配，报了只会误导）")
    void shouldSkipLeaseChecksWhenDisabled() {
        lease.setEnabled(false);
        lease.setTtl(Duration.ZERO);
        internal.setToken("shared-secret");

        BusinessStartupChecker checker = new BusinessStartupChecker(internal, lease);

        assertThat(checker.errors()).isEmpty();
        assertThat(checker.warnings()).isEmpty();
    }

    @Test
    @DisplayName("自检入口本身可执行：WARN 与 ERROR 两条日志分支都被走到，且不抛异常")
    void afterPropertiesSetShouldLogBothBranches() {
        // 同时制造「错误」与「警告」：ttl 太短 + 凭证未配 + 租户为空
        lease.setTtl(Duration.ofSeconds(1));

        BusinessStartupChecker checker = new BusinessStartupChecker(internal, lease);

        checker.afterPropertiesSet();

        assertThat(checker.errors()).isNotEmpty();
        assertThat(checker.warnings()).isNotEmpty();
    }
}
