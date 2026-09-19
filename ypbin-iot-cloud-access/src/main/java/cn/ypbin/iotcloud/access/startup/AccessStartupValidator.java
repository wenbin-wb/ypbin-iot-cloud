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
import cn.ypbin.starter.core.util.LogSanitizer;
import feign.Request;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
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
    private final ObjectProvider<Request.Options> requestOptions;

    /**
     * 构造启动自检。
     *
     * @param properties     本节点参数
     * @param requestOptions 生效的 Feign 超时配置（<b>可被宿主覆盖</b>）
     */
    public AccessStartupValidator(AccessProperties properties, ObjectProvider<Request.Options> requestOptions) {
        this.properties = properties;
        this.requestOptions = requestOptions;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> issues = errors();
        if (!issues.isEmpty()) {
            throw new IllegalStateException("access 启动自检未通过：" + String.join("；", issues));
        }
        log.info("access 启动自检通过：node={} renewIntervalMs={} 续约最坏耗时={}ms（要求倍数≥{}）",
            LogSanitizer.sanitize(properties.getNodeId()), properties.getRenewIntervalMs(), worstCaseMs(),
            SAFETY_FACTOR);
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
        if (properties.getAcquireIntervalMs() <= 0) {
            issues.add(AccessProperties.PREFIX
                + ".acquire-interval-ms 必须为正数（当前 " + properties.getAcquireIntervalMs()
                + "）：为 0 会让每个调度 tick 都重领一次");
        }
        // 残余窗口加固（复核建议）：重领间隔若小于「一次续约/领取的最坏耗时」，
        // 调度器可能在握手 acquire 还没回来时就到点重领一次（同一 nodeId、幂等，无安全影响，
        // 但会重复 RPC 并让指标虚增）。默认 15s ≥ 8s 通过。
        if (properties.getAcquireIntervalMs() > 0 && properties.getAcquireIntervalMs() < worstCaseMs()) {
            issues.add(AccessProperties.PREFIX + ".acquire-interval-ms（" + properties.getAcquireIntervalMs()
                + "ms）必须 ≥ 一次调用的最坏耗时(" + worstCaseMs()
                + "ms)：否则握手 acquire 还在飞行时就到点重领，造成重复 RPC 与指标虚增");
        }
        long required = (long) worstCaseMs() * SAFETY_FACTOR;
        if (properties.getRenewIntervalMs() < required) {
            issues.add(AccessProperties.PREFIX + ".renew-interval-ms（" + properties.getRenewIntervalMs()
                + "ms）必须 ≥ 一次续约最坏耗时（connect " + optionsOrDefault().connectTimeoutMillis()
                + "ms + read " + optionsOrDefault().readTimeoutMillis() + "ms = " + worstCaseMs()
                + "ms）的 " + SAFETY_FACTOR + " 倍（即 ≥" + required
                + "ms）：否则一次卡顿就会让续约跨过到期时间、把自己卡成失效节点；"
                + "若覆盖了 Feign 超时，请同步调整本值");
        }
        return issues;
    }

    /**
     * 一次续约的最坏耗时（毫秒）：连接超时 + 读超时（不重试，见 {@code LeaseFeignConfiguration}）。
     *
     * <p>⚠️ 优先读<b>生效的</b> {@link Request.Options}：那两个 Bean 都是
     * {@code @ConditionalOnMissingBean(SearchStrategy.ALL)}，宿主可以覆盖（例如把 read 调成 30s）。
     * 只看契约常量会让本校验变成<b>假绿门禁</b>——复核 D5 实测过这一点。
     * 主上下文里没有该 Bean 时才回落到契约常量（Feign 子上下文里的默认值就是这两个常量）。</p>
     */
    private int worstCaseMs() {
        Request.Options options = optionsOrDefault();
        return options.connectTimeoutMillis() + options.readTimeoutMillis();
    }

    /**
     * 生效的超时配置：主上下文有就用它，没有就用契约常量兜一个等价对象。
     *
     * <p>用于让<b>报错消息里的分解与实际取值一致</b>（复核指出：宿主把 read 覆盖成 30s 时，
     * 旧消息仍打印「read 3000ms」而总和是 31000ms，运维会看糊涂）。</p>
     *
     * @return 生效的 Feign 超时配置
     */
    private Request.Options optionsOrDefault() {
        Request.Options options = requestOptions.getIfAvailable();
        if (options != null) {
            return options;
        }
        return new Request.Options(LeaseFeignConfiguration.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS,
            LeaseFeignConfiguration.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS, true);
    }
}
