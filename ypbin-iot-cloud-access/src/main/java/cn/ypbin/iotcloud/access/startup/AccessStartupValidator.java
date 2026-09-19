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

import cn.ypbin.iotcloud.access.config.AccessProperties;
import cn.ypbin.iotcloud.api.lease.config.LeaseFeignConfiguration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * access 的启动期自检：<b>不通过就不启动</b>。
 *
 * <p>与 business 的启动自检（只记 ERROR/WARN）不同，这里的两条都是 <b>fail-fast</b>：
 * business 配错租约只会让接管抖动，而 access 配错会直接导致「采集乱套」——</p>
 * <ol>
 *   <li><b>node-id 为空</b>：租约归属以节点标识为键，为空会让所有副本注册成同一个节点，
 *       归属与 fencing 全部失效；</li>
 *   <li><b>续约周期不够长</b>：契约 §6 要求「单次续约最坏耗时 &lt;&lt; 续约周期」。
 *       超时值来自 {@link LeaseFeignConfiguration}（connect {@value LeaseFeignConfiguration#CONNECT_TIMEOUT_MS}ms
 *       + read {@value LeaseFeignConfiguration#READ_TIMEOUT_MS}ms），这里要求续约周期至少是它的
 *       {@value #SAFETY_FACTOR} 倍。宿主若覆盖了该 Feign 配置，须同步调整本值
 *       （契约 §6「超时/重试的宿主覆盖」那行说的就是这件事）。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Component
public class AccessStartupValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AccessStartupValidator.class);

    /** 续约周期相对「一次续约最坏耗时」的最小倍数（留出抖动与业务处理余量）。 */
    static final int SAFETY_FACTOR = 2;

    private final AccessProperties properties;

    /**
     * 构造启动自检。
     *
     * @param properties 本节点参数
     */
    public AccessStartupValidator(AccessProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> issues = errors();
        if (!issues.isEmpty()) {
            throw new IllegalStateException("access 启动自检未通过：" + String.join("；", issues));
        }
        log.info("access 启动自检通过：node={} renewIntervalMs={} 续约最坏耗时={}ms（要求倍数≥{}）",
            properties.getNodeId(), properties.getRenewIntervalMs(), worstCaseMs(), SAFETY_FACTOR);
    }

    /**
     * 启动期问题清单（纯函数，便于直接断言）。
     *
     * @return 问题描述列表；为空表示可以启动
     */
    List<String> errors() {
        List<String> issues = new ArrayList<>();
        if (!StringUtils.hasText(properties.getNodeId())) {
            issues.add(AccessProperties.PREFIX
                + ".node-id 不能为空：租约归属以节点标识为键，为空会让所有副本注册成同一个节点");
        }
        long required = (long) worstCaseMs() * SAFETY_FACTOR;
        if (properties.getRenewIntervalMs() < required) {
            issues.add(AccessProperties.PREFIX + ".renew-interval-ms（" + properties.getRenewIntervalMs()
                + "ms）必须 ≥ 一次续约最坏耗时（connect " + LeaseFeignConfiguration.CONNECT_TIMEOUT_MS
                + "ms + read " + LeaseFeignConfiguration.READ_TIMEOUT_MS + "ms = " + worstCaseMs()
                + "ms）的 " + SAFETY_FACTOR + " 倍（即 ≥" + required
                + "ms）：否则一次卡顿就会让续约跨过到期时间、把自己卡成失效节点；"
                + "若覆盖了 LeaseFeignConfiguration 的超时，请同步调整本值");
        }
        return issues;
    }

    /** 一次续约的最坏耗时（毫秒）：连接超时 + 读超时（不重试，见 LeaseFeignConfiguration）。 */
    private int worstCaseMs() {
        return LeaseFeignConfiguration.CONNECT_TIMEOUT_MS + LeaseFeignConfiguration.READ_TIMEOUT_MS;
    }
}
