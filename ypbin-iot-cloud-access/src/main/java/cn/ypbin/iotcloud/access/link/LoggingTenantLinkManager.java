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
package cn.ypbin.iotcloud.access.link;

import cn.ypbin.starter.core.util.LogSanitizer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M0a 的链路管理实现：<b>只记录状态 + 打日志，不真的建链</b>。
 *
 * <p>M0a 还没有协议栈（{@code ypbin-iot-bom} 未发布），所以「断链」在这里等于「把租户从采集集合里摘掉」。
 * 这不是占位符糊弄：self-fencing 的<b>可观测与可断言</b>部分（谁在采、谁必须停、停了没有）现在就是完整的，
 * P4b 接上 iot-starter 时替换本实现即可，判定逻辑（{@code AccessLeaseManager}）不用改。</p>
 *
 * <p>⚠️ 本类<b>不是</b> {@code @Component}：它由 {@code AccessLeaseConfiguration} 以
 * {@code @Bean @ConditionalOnMissingBean} 装配，这样「宿主提供自己的实现」才真的成立——
 * 无条件 {@code @Component} + 宿主再定义一个 {@code TenantLinkManager} bean 会直接
 * {@code NoUniqueBeanDefinitionException}（复核 D8）。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
public class LoggingTenantLinkManager implements TenantLinkManager {

    private static final Logger log = LoggerFactory.getLogger(LoggingTenantLinkManager.class);

    /** 当前正在采集的租户（热路径只读内存，不查库不调 RPC，spec §3.1① I5）。 */
    private final Set<Long> collecting = ConcurrentHashMap.newKeySet();

    @Override
    public void startCollecting(Long tenantId) {
        if (collecting.add(tenantId)) {
            log.info("开始采集租户：tenantId={}（M0a 无协议栈，仅状态标记）", LogSanitizer.sanitize(tenantId));
        }
    }

    @Override
    public void fence(Long tenantId, String reason) {
        if (collecting.remove(tenantId)) {
            log.warn("租户已断链停采（self-fencing）：tenantId={} reason={}",
                LogSanitizer.sanitize(tenantId), LogSanitizer.sanitize(reason));
        }
    }

    @Override
    public void fenceAll(String reason) {
        int size = collecting.size();
        collecting.clear();
        log.warn("本节点整体断链停采（self-fencing）：原采集租户数={} reason={}", size,
            LogSanitizer.sanitize(reason));
    }

    @Override
    public boolean isCollecting(Long tenantId) {
        return collecting.contains(tenantId);
    }

    @Override
    public Set<Long> collectingTenants() {
        return Set.copyOf(collecting);
    }
}
