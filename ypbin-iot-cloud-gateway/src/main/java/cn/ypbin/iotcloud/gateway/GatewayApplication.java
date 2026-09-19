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
package cn.ypbin.iotcloud.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 部署单元① 网关的启动类。
 *
 * <p><b>P2 已落地</b>路由、鉴权、伪造头剥离与<b>租户上下文的注入通路</b>（见 IOT-CLOUD-SPEC.md §4.1 / §4.4）；
 * 具体「谁能被注入租户身份」取决于认证服务，属 M0b（M0a 的 Provider 为 fail-closed 占位）。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
@SpringBootApplication
public class GatewayApplication {

    /**
     * 应用入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
