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

import cn.ypbin.iotcloud.business.web.InternalLeaseController;
import cn.ypbin.iotcloud.core.lease.LeaseProperties;
import cn.ypbin.iotcloud.core.lease.LeaseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * 「关掉租约维护后 business 仍能启动」的门禁用例。
 *
 * <p>来历：修 P3 复核问题时，启动自检无条件注入了 {@link LeaseProperties}，而注册它的自动配置是
 * <b>类级</b> {@code @ConditionalOnProperty(ypbin.lease.enabled)}——于是
 * {@code --ypbin.lease.enabled=false} 会让属性 bean 一起消失，应用直接
 * {@code APPLICATION FAILED TO START}。也就是说「关掉租约维护」变成了「把服务弄挂」，
 * 而原先的自动配置用例用的是裸 {@code ApplicationContextRunner}、不含 business 的组件，**抓不到**。</p>
 *
 * <p>本用例用真实应用上下文把这个组合钉住：<b>属性 bean 必须在、租约组件必须不在</b>。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@SpringBootTest(properties = {
    "ypbin.internal.token=test-internal-token-disabled",
    "ypbin.lease.enabled=false"
})
class BusinessWithoutLeaseContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("ypbin.lease.enabled=false：应用能正常启动，属性 bean 在、租约组件与端点都不在")
    void contextShouldStartWithoutLeaseComponents() {
        assertThat(context.getBean(LeaseProperties.class)).isNotNull();
        assertThat(context.getBeanNamesForType(LeaseService.class)).isEmpty();
        // 端点是「随开关一起不注册」的：功能关掉的语义是端点不存在，而不是端点还在但一调就报错
        assertThat(context.getBeanNamesForType(InternalLeaseController.class)).isEmpty();
    }
}
