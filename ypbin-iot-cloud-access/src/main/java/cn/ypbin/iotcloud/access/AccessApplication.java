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

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 部署单元③ 设备接入的启动类（有状态）。
 *
 * <p><b>M0a 只有骨架</b>：P0 故意不接 ypbin-iot-starter（它尚未发布），
 * 也不含租约领取/续约与 self-fencing 状态机（P4 落地，IOT-CLOUD-SPEC.md §3.1①）。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
@SpringBootApplication
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
