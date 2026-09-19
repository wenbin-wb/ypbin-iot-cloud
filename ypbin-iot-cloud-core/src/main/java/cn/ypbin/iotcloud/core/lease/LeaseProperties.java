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

import java.time.Duration;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 租约维护参数（IOT-CLOUD-SPEC.md §3.1① 的失效检测与续约窗口）。
 *
 * <p>三个参数的取值互相约束，改之前先读注释：</p>
 * <ul>
 *   <li>{@link #ttl} 必须<b>明显大于</b> access 的续约周期（默认 10s），否则一次网络抖动就会让租约被判过期；</li>
 *   <li>{@link #scanIntervalMs} 决定「节点退出 → 置为待接管」的<b>最坏延迟</b>；
 *       它同时也是「新旧节点同时轮询同一台设备」这个风险窗口的长度（§3.1① 的 self-fencing 就是为了兜住它）；</li>
 *   <li>{@link #assignableTenantIds} 是 M0a 的临时租户来源——M0a 没有台账表，M0b 换成从表里读。</li>
 * </ul>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Getter
@Setter
@ConfigurationProperties(prefix = LeaseProperties.PREFIX)
public class LeaseProperties {

    /** 配置前缀。 */
    public static final String PREFIX = "ypbin.lease";

    /** 是否启用租约维护；关掉后不装配扫描器（本地只想跑业务接口时用）。 */
    private boolean enabled = true;

    /** 租约有效期：必须明显大于 access 的续约周期（默认 10s），否则抖动即被判过期。 */
    private Duration ttl = Duration.ofSeconds(30);

    /** 失效扫描周期（毫秒）：也是「节点退出 → 待接管」的最坏延迟。 */
    private long scanIntervalMs = 15_000L;

    /**
     * M0a 的可分配租户清单。
     *
     * <p>M0a 还没有台账表（迁移在 M0b 定稿，§12.2），所以「有哪些租户可以被分配给 access 节点」
     * 暂时由配置给出；M0b 换成从台账表读取。**留空时不会有任何租户被分配**（不会凭空造租户）。</p>
     */
    private List<Long> assignableTenantIds = List.of();
}
