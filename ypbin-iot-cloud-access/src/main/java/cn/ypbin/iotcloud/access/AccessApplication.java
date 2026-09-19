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
package cn.ypbin.iotcloud.access;

import cn.ypbin.iotcloud.access.config.AccessProperties;
import cn.ypbin.iotcloud.api.lease.ILeaseClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 部署单元③ 设备接入的启动类（有状态）。
 *
 * <p><b>P4 落地了租约客户端与 self-fencing</b>：注册 → 领取 → 周期续约（默认 10s）→ 本地过期自检，
 * 并把「该停采」变成真的停采（{@code TenantLinkManager}）。M0a 仍<b>不接协议栈</b>——
 * {@code ypbin-iot-bom} 尚未发布，所以链路管理现在只做状态标记与日志，
 * 但 spec §3.1① 要求的 self-fencing 判定语义已经完整且被用例钉住。</p>
 *
 * <p>{@code @EnableFeignClients} 只为 {@link ILeaseClient}；服务发现在 M0a 用 Spring Cloud 的
 * {@code simple} 发现（关 Nacos，spec §4.5），business 地址在 yml 里配。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
@SpringBootApplication
@EnableFeignClients(clients = ILeaseClient.class)
@EnableScheduling
@EnableConfigurationProperties(AccessProperties.class)
public class AccessApplication {

    /**
     * 应用入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(AccessApplication.class, args);
    }
}
