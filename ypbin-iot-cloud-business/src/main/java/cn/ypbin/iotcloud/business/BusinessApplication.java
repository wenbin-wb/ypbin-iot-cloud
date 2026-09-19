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
package cn.ypbin.iotcloud.business;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 部署单元② 业务服务的启动类。
 *
 * <p>装配 auth / core / openapi 三个库。P3 起承担两件事：① 暴露 {@code /internal/lease/**}
 * （access 节点的注册/领取/续约/释放/对账，契约见 {@code ILeaseClient}）；② 跑租约失效扫描
 * （{@code @EnableScheduling} 就是为它开的，见 core 的 {@code LeaseExpiryScanner}）。</p>
 *
 * <p>数据库在 M0a 不接：Flyway 迁移形态与 schema 校验在 M0b 定稿（§12.2）。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
@SpringBootApplication
@EnableScheduling
public class BusinessApplication {

    /**
     * 应用入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(BusinessApplication.class, args);
    }
}
