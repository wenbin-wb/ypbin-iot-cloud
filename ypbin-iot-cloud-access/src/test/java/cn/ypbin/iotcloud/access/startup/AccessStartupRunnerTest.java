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
package cn.ypbin.iotcloud.access.startup;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import cn.ypbin.iotcloud.access.lease.AccessLeaseManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 启动握手的 runner 测试。
 *
 * <p>P4 的硬要求是「{@code register} 的任何非 {@code code=200} 都必须当启动失败」——
 * 落地方式就是这个 runner 直接调用状态机的 {@code start()} 并让异常冒出去
 * （{@code SpringApplication.run} 因此失败退出，节点不会在「没有租户」的状态下开始采集）。
 * 所以这里要钉住两件事：真的调了 {@code start()}、异常真的往外冒。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
class AccessStartupRunnerTest {

    @Test
    @DisplayName("握手：调用状态机 start()")
    void runShouldInvokeStart() {
        AccessLeaseManager manager = mock(AccessLeaseManager.class);

        new AccessStartupRunner(manager).run(null);

        verify(manager).start();
    }

    @Test
    @DisplayName("握手失败（注册/领取未成功）→ 异常必须往外冒，让应用启动失败")
    void runShouldPropagateStartFailure() {
        AccessLeaseManager manager = mock(AccessLeaseManager.class);
        doThrow(new IllegalStateException("access 启动失败：注册节点未成功")).when(manager).start();

        assertThatThrownBy(() -> new AccessStartupRunner(manager).run(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("启动失败");
    }
}
