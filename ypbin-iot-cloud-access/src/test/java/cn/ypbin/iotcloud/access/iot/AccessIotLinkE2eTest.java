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
package cn.ypbin.iotcloud.access.iot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cn.ypbin.iot.core.spi.DataSink;
import cn.ypbin.iot.spring.autoconfigure.IotLifecycle;
import cn.ypbin.iotcloud.access.link.TenantLinkManager;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * P4b 的端到端证明：<b>租约归属真的变成建链，撤销真的变成断链</b>。
 *
 * <p>与前一个用例（mock 掉 {@code IotLifecycle}）的区别：这里用的是 iot-starter 的<b>真实</b>装配
 * （真实 TCP 适配器 + 真实 Netty 传输）与一个真实的 TCP 服务端（{@link ServerSocket} 动态端口），
 * 断言直接落在 {@code IotLifecycle.sessionCount()} 上——即「会话真的建了/真的关了」，
 * 而不是「某个方法被调用了」。</p>
 *
 * <p>关掉启动握手（{@code startup-handshake-enabled=false}）是为了不依赖 business：
 * 本用例只验证协议栈侧的真建链/真断链，租约侧的判定另有专门用例与真实进程验收。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@SpringBootTest(properties = {
    "ypbin.internal.token=test-internal-token",
    "ypbin.access.node-id=access-iot-e2e",
    "ypbin.access.startup-handshake-enabled=false",
    "ypbin.access.device-connect-timeout=2s",
    "ypbin.access.device-request-timeout=2s"
})
class AccessIotLinkE2eTest {

    private static final long TENANT = 11L;

    /** 真实 TCP 服务端（动态端口）：只接受连接并保持，模拟一台透传设备。 */
    private static final ServerSocket DEVICE_SERVER;

    /** 服务端侧收到的连接（用于证明「连上过」与「已断开」）。 */
    private static final List<Socket> ACCEPTED = new CopyOnWriteArrayList<>();

    static {
        try {
            DEVICE_SERVER = new ServerSocket(0);
        } catch (IOException ex) {
            throw new IllegalStateException("无法启动模拟 TCP 设备（测试前置）", ex);
        }
        Thread acceptor = new Thread(() -> {
            while (!DEVICE_SERVER.isClosed()) {
                try {
                    ACCEPTED.add(DEVICE_SERVER.accept());
                } catch (IOException ex) {
                    return;
                }
            }
        }, "e2e-tcp-device");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @DynamicPropertySource
    static void deviceProperties(DynamicPropertyRegistry registry) {
        registry.add("ypbin.access.devices[0].tenant-id", () -> TENANT);
        registry.add("ypbin.access.devices[0].device-id", () -> "dev-e2e-11");
        registry.add("ypbin.access.devices[0].connection-id", () -> "conn-e2e-11");
        registry.add("ypbin.access.devices[0].uri", () -> "tcp://127.0.0.1:" + DEVICE_SERVER.getLocalPort());
    }

    @Autowired
    private TenantLinkManager linkManager;

    @Autowired
    private IotLifecycle lifecycle;

    @Autowired
    private DataSink dataSink;

    @Test
    @DisplayName("classpath 上有 iot-starter 时：链路管理换成真建链实现（接缝生效），出口是本次实现的出口")
    void iotStarterOnClasspathShouldReplaceLinkManager() {
        assertThat(linkManager).isInstanceOf(IotTenantLinkManager.class);
        assertThat(dataSink).isInstanceOf(LoggingDataSink.class);
    }

    /** 收尾：关掉模拟设备与它接受过的连接（静态资源不关会跨用例泄漏端口与线程）。 */
    @AfterAll
    static void shutdownDeviceServer() throws IOException {
        for (Socket socket : ACCEPTED) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 已关闭/已断开：收尾阶段无需处理
            }
        }
        ACCEPTED.clear();
        if (!DEVICE_SERVER.isClosed()) {
            DEVICE_SERVER.close();
        }
    }

    @Test
    @DisplayName("开始采集 → iot-starter 真的建立会话；撤销租约 → 真的关闭会话（真建链/真断链）")
    void startCollectingShouldReallyConnectAndFenceShouldReallyDisconnect() {
        assertThat(lifecycle.sessionCount()).isZero();

        linkManager.startCollecting(TENANT);

        // 真建链：框架侧会话数 0 → 1（探测也真的连过一次，见 ACCEPTED）
        await().atMost(Duration.ofSeconds(15)).until(() -> lifecycle.sessionCount() == 1);
        await().atMost(Duration.ofSeconds(5)).until(() -> !ACCEPTED.isEmpty());

        linkManager.fence(TENANT, "e2e：租约被撤销");

        // 真断链：框架侧会话数回到 0
        await().atMost(Duration.ofSeconds(15)).until(() -> lifecycle.sessionCount() == 0);
        assertThat(linkManager.isCollecting(TENANT)).isFalse();
    }
}
