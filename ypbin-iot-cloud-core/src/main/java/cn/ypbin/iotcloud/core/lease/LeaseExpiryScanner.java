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
package cn.ypbin.iotcloud.core.lease;

import cn.ypbin.iotcloud.api.lease.LeaseAssignmentDto;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 租约失效扫描（IOT-CLOUD-SPEC.md §3.1① 的「定时扫描 → 置为待接管」）。
 *
 * <p>这个类只做三件事：按周期跑、把当前时刻交给 {@link LeaseService#markExpired(LocalDateTime)}、
 * 有变化时留下一条可检索的 WARN。判定逻辑全部在 {@link LeaseService} 里，
 * 这样「扫描」这个不可控的调度入口尽可能薄，行为可以脱离调度器被测试。</p>
 *
 * <p>⚠️ <b>多副本问题（M0b 必解）</b>：本类是进程内的 {@code @Scheduled}，N 个 business 副本会各扫一遍。
 * M0a 内存存储下每个副本只看自己的内存、不会互相破坏；但 M0b 换成共享表后，
 * 「读出来再写回去」必须改成单写者或原子 {@code UPDATE ... WHERE}（见 ADR-0001）。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
public class LeaseExpiryScanner {

    private static final Logger log = LoggerFactory.getLogger(LeaseExpiryScanner.class);

    private final LeaseService leaseService;

    /**
     * 构造失效扫描器。
     *
     * @param leaseService 租约维护服务（判定逻辑都在它里面）
     */
    public LeaseExpiryScanner(LeaseService leaseService) {
        this.leaseService = leaseService;
    }

    /** 按配置周期扫描过期租约；周期见 {@code ypbin.lease.scan-interval-ms}。 */
    @Scheduled(fixedDelayString = "${ypbin.lease.scan-interval-ms:15000}")
    public void scan() {
        List<LeaseAssignmentDto> marked = leaseService.markExpired(LocalDateTime.now());
        if (!marked.isEmpty()) {
            log.warn("租约失效扫描：{} 个租户被置为待接管，租户={}", marked.size(),
                marked.stream().map(LeaseAssignmentDto::getTenantId).toList());
        }
    }
}
