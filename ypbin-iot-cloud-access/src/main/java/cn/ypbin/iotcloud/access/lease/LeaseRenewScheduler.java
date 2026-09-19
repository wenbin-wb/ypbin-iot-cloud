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
package cn.ypbin.iotcloud.access.lease;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期续约入口（默认 10s，spec §3.1①）。
 *
 * <p>只做一件事：把调度交给 {@link AccessLeaseManager#renewAndSelfCheck()}。
 * 判定逻辑放在状态机里，这样「续约 + 本地过期自检」可以脱开调度器被测试
 * （本类因此没有分支可测，只有一行转调）。</p>
 *
 * <p>⚠️ 注解里的默认值必须与 {@code AccessProperties.renewIntervalMs} 的默认值一致，
 * 否则改一处会静默漂移——有专门用例钉住这对默认值。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Component
public class LeaseRenewScheduler {

    private final AccessLeaseManager leaseManager;

    /**
     * 构造调度器。
     *
     * @param leaseManager 租约状态机
     */
    public LeaseRenewScheduler(AccessLeaseManager leaseManager) {
        this.leaseManager = leaseManager;
    }

    /** 按配置周期续约；周期见 {@code ypbin.access.renew-interval-ms}。 */
    @Scheduled(fixedDelayString = "${ypbin.access.renew-interval-ms:10000}")
    public void renew() {
        leaseManager.renewAndSelfCheck();
    }
}
