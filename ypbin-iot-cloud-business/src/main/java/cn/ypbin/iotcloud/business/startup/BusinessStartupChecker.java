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
package cn.ypbin.iotcloud.business.startup;

import cn.ypbin.iotcloud.api.lease.config.LeaseFeignConfiguration;
import cn.ypbin.iotcloud.common.config.InternalProperties;
import cn.ypbin.iotcloud.common.constant.InternalTokenConstants;
import cn.ypbin.iotcloud.core.lease.LeaseProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * business 的启动期自检（把「运维提醒」变成可执行约束）。
 *
 * <p>背景：有两类配置错误<b>不会让服务起不来</b>，只会在运行期以很难排查的方式发作，
 * 所以必须在这里大声说出来：</p>
 * <ol>
 *   <li><b>租约有效期不够长</b>：一次网络抖动就会让续约跨过到期时间，把还活着的节点判成失效、
 *       触发无谓接管（契约 §6「超时与续约周期的关系」明确要求 P3/P4 加启动期校验）。
 *       判据是 {@code ttl > 预期续约周期 + 一次续约最坏耗时}——只看「ttl &gt; 周期」会漏掉
 *       「周期够长、但一次续约本身就能耗掉大半个周期」的配置（复核 D6 实测：{@code ttl=11s}
 *       能过旧判据，而客户端最坏周期是 10s + connect 1s + read 3s = 14s）；</li>
 *   <li><b>没配内部凭证 / 没配可分配租户</b>：前者让守卫 fail-closed 拒绝一切
 *       {@code /internal/**} 请求（安全上正确，但本地联调常忘）；后者让整条租约链路空跑
 *       （{@code acquire} 永远返回空、release 全部被忽略）——独立复核就把这一条列为「差点被假绿骗过」。</li>
 *   <li>租约参数自身不合法（{@code ttl} 或 {@code expected-renew-interval} 为 0/负数）：不查它自己，
 *       「有效期是否足够」的判定会被静默绕过。</li>
 * </ol>
 *
 * <p>校验逻辑与日志分离成 {@link #errors()} / {@link #warnings()} 两个纯方法，便于直接断言。</p>
 *
 * <p><b>为什么是 ERROR 日志而不是 fail-fast</b>（独立复核提过一次，这里记录取舍）：M0a 允许「先起来看日志」，
 * 把配置错误升级成起不来会让本地联调更难；而这些错误的后果是<b>无谓接管/抖动</b>而非数据损坏。
 * 代价是「配置错了但服务看起来健康」——所以 INFO 行会说清关掉开关时少了什么，
 * 而把自检接入 {@code /actuator/health} 已登记为 M0b 待办。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Component
public class BusinessStartupChecker implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(BusinessStartupChecker.class);

    private final InternalProperties internalProperties;
    private final LeaseProperties leaseProperties;

    /**
     * 构造启动自检。
     *
     * @param internalProperties 内部调用配置（守卫用）
     * @param leaseProperties    租约参数
     */
    public BusinessStartupChecker(InternalProperties internalProperties, LeaseProperties leaseProperties) {
        this.internalProperties = internalProperties;
        this.leaseProperties = leaseProperties;
    }

    @Override
    public void afterPropertiesSet() {
        if (!leaseProperties.isEnabled()) {
            log.info("租约维护已关闭（{}）：/internal/lease/** 端点不会注册，失效扫描也不启动",
                LeaseProperties.PREFIX + ".enabled");
        }
        for (String warning : warnings()) {
            log.warn("业务服务启动自检：{}", warning);
        }
        for (String error : errors()) {
            log.error("业务服务启动自检：{}", error);
        }
    }

    /**
     * 启动期<b>错误</b>：不阻断启动（M0a 允许先起来看日志），但线上必然出问题。
     *
     * @return 问题描述列表
     */
    List<String> errors() {
        List<String> issues = new ArrayList<>();
        if (!leaseProperties.isEnabled()) {
            // 关掉租约维护时这些检查没有意义（组件都没装配），报出来只会误导排查
            return issues;
        }
        Duration ttl = leaseProperties.getTtl();
        Duration renewInterval = leaseProperties.getExpectedRenewInterval();
        // 不校验参数自身会让「有效期是否足够」形同虚设：interval 配 0 时任何 ttl 都「大于」它。
        // 注意**不早退**：一次启动要把所有配置问题都列出来，否则运维改一条重启一次才能看到下一条。
        boolean intervalValid = !renewInterval.isZero() && !renewInterval.isNegative();
        boolean ttlValid = !ttl.isZero() && !ttl.isNegative();
        if (!intervalValid) {
            issues.add("ypbin.lease.expected-renew-interval 必须为正数（当前 " + renewInterval
                + "）：为 0 或负数会让「租约有效期是否足够」的校验静默失效");
        }
        if (!ttlValid) {
            issues.add("ypbin.lease.ttl 必须为正数（当前 " + ttl + "）");
        }
        if (intervalValid && ttlValid && ttl.compareTo(renewInterval.plusMillis(worstRequestMs())) <= 0) {
            issues.add("租约有效期(" + ttl + ")必须大于「预期续约周期(" + renewInterval + ") + 一次续约最坏耗时("
                + worstRequestMs() + "ms)」：否则一次卡顿就会让续约跨过到期时间、把活着的节点判成失效并触发接管"
                + "（最坏耗时按客户端 connect " + LeaseFeignConfiguration.CONNECT_TIMEOUT_MS + "ms + read "
                + LeaseFeignConfiguration.READ_TIMEOUT_MS + "ms、不重试计算）");
        }
        return issues;
    }

    /**
     * 一次续约的最坏耗时（毫秒）：客户端 connect + read，且不重试。
     *
     * <p>取值与客户端 {@code LeaseFeignConfiguration} 的契约常量一致；客户端覆盖了超时时，
     * 本侧的「ttl 是否足够」判断会偏乐观——这是已知的跨进程约束，写在契约 §6 里。</p>
     *
     * @return 最坏耗时毫秒数
     */
    private int worstRequestMs() {
        return LeaseFeignConfiguration.CONNECT_TIMEOUT_MS + LeaseFeignConfiguration.READ_TIMEOUT_MS;
    }

    /**
     * 启动期<b>警告</b>：多半是配置漏了，不一定是错误。
     *
     * @return 提醒列表
     */
    List<String> warnings() {
        List<String> issues = new ArrayList<>();
        if (!StringUtils.hasText(internalProperties.getToken())) {
            issues.add("未配置 " + InternalTokenConstants.TOKEN_PROPERTY
                + "：入站守卫会 fail-closed 拒绝全部 /internal/** 请求（安全上正确，但本地联调常忘）");
        }
        if (leaseProperties.isEnabled() && leaseProperties.getAssignableTenantIds().isEmpty()) {
            issues.add("ypbin.lease.assignable-tenant-ids 为空：不会有任何租户被分配，租约链路会空跑"
                + "（M0a 这是默认值；跑验收时请显式传入租户）");
        }
        return issues;
    }
}
