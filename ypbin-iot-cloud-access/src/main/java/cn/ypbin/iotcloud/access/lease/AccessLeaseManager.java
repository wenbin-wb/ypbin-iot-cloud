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

import cn.ypbin.iotcloud.access.config.AccessProperties;
import cn.ypbin.iotcloud.access.link.TenantLinkManager;
import cn.ypbin.iotcloud.api.lease.AccessNodeRegisterReq;
import cn.ypbin.iotcloud.api.lease.ILeaseClient;
import cn.ypbin.iotcloud.api.lease.LeaseAcquireReq;
import cn.ypbin.iotcloud.api.lease.LeaseAcquireResp;
import cn.ypbin.iotcloud.api.lease.LeaseAssignmentDto;
import cn.ypbin.iotcloud.api.lease.LeaseRenewAck;
import cn.ypbin.iotcloud.api.lease.LeaseRenewItem;
import cn.ypbin.iotcloud.api.lease.LeaseRenewReq;
import cn.ypbin.iotcloud.api.lease.LeaseRenewResp;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.core.util.LogSanitizer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * access 侧的租约状态机：注册 → 领取 → 周期续约 → <b>self-fencing</b>（部署单元③，spec §3.1①）。
 *
 * <p>三类行为是 P4 的交付核心：</p>
 * <ol>
 *   <li><b>注册失败即启动失败</b>：{@code register} 是「开始采集」的前置，任何非 {@code code=200}
 *       （含契约文档里写明的「租约维护被关闭 ⇒ 端点不注册 ⇒ 404」）都必须让节点<b>不要开始采集</b>，
 *       而不是重试或降级（契约 §6 的 P4 硬要求）；</li>
 *   <li><b>续约响应驱动停采</b>：{@code revokedTenantIds} → 对应用户端断链停采；
 *       {@code nodeFenced} → 整体停采后<b>重新注册</b>（节点级失效不是永久性的）；</li>
 *   <li><b>本地过期自检（不等 business）</b>：续约失败/超时导致本地到期时间没被延长时，
 *       节点必须<b>自己</b>在到期后断链停采——这正是 v3 缺失的能力：不能指望新节点去断旧节点的 socket，
 *       旧节点若是长 GC 停顿或网络分区，进程还活着、socket 还开着，会出现新旧同时轮询同一台设备。</li>
 * </ol>
 *
 * <p><b>可观测（P4 退出条件）</b>：注册/领取/续约成功与失败、被撤销、本地自 fencing、节点级 fencing
 * 都有计数器，当前持有与在采租户数是 gauge，可直接从 {@code /actuator/metrics} 查。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
public class AccessLeaseManager {

    private static final Logger log = LoggerFactory.getLogger(AccessLeaseManager.class);

    /** 指标前缀。 */
    static final String METRIC_PREFIX = "iotcloud.lease.";

    private final ILeaseClient leaseClient;
    private final TenantLinkManager linkManager;
    private final AccessProperties properties;
    private final Counter renewSuccess;
    private final Counter renewFailure;
    private final Counter revoked;
    private final Counter selfFenced;
    private final Counter acquiredCounter;
    private final Counter nodeFenced;

    /** 本地持有的租户租约（热路径只读它，不查库不调 RPC）。 */
    private final Map<Long, LeaseSnapshot> holdings = new ConcurrentHashMap<>();

    /** 上次「周期重领」的时刻（接管孤儿租户的入口，见 {@link AccessProperties#getAcquireIntervalMs()}）。 */
    private final AtomicReference<LocalDateTime> lastAcquireAt = new AtomicReference<>();

    /**
     * 构造租约状态机。
     *
     * @param leaseClient   business 侧的租约契约客户端（Feign）
     * @param linkManager   采集链路控制端口（self-fencing 的执行面）
     * @param properties    本节点参数
     * @param meterRegistry 指标注册表（可观测性，P4 退出条件）
     */
    public AccessLeaseManager(ILeaseClient leaseClient, TenantLinkManager linkManager,
            AccessProperties properties, MeterRegistry meterRegistry) {
        this.leaseClient = leaseClient;
        this.linkManager = linkManager;
        this.properties = properties;
        this.renewSuccess = Counter.builder(METRIC_PREFIX + "renew.success")
            .description("续约成功次数").register(meterRegistry);
        this.renewFailure = Counter.builder(METRIC_PREFIX + "renew.failure")
            .description("续约失败次数（异常或非成功信封）").register(meterRegistry);
        this.revoked = Counter.builder(METRIC_PREFIX + "revoked")
            .description("因 business 撤销/接管而停采的租户次数").register(meterRegistry);
        this.selfFenced = Counter.builder(METRIC_PREFIX + "self_fenced")
            .description("因本地租约过期而自行停采的租户次数").register(meterRegistry);
        this.acquiredCounter = Counter.builder(METRIC_PREFIX + "acquired")
            .description("周期重领新接管的租户次数").register(meterRegistry);
        this.nodeFenced = Counter.builder(METRIC_PREFIX + "node_fenced")
            .description("节点级 fencing 次数（business 判定本节点失效）").register(meterRegistry);
        Gauge.builder(METRIC_PREFIX + "held", holdings, Map::size)
            .description("本地持有的租户数").register(meterRegistry);
        Gauge.builder(METRIC_PREFIX + "collecting", linkManager, manager -> manager.collectingTenants().size())
            .description("当前在采租户数").register(meterRegistry);
    }

    /**
     * 启动握手：注册（失败即抛）+ 领取。由 {@code AccessStartupRunner} 在启动期调用。
     *
     * @throws IllegalStateException 注册或领取未成功（节点不得开始采集）
     */
    public void start() {
        registerOrFail();
        acquireOrFail();
        lastAcquireAt.set(LocalDateTime.now());
    }

    /**
     * 周期续约 + 本地过期自检。
     *
     * <p>顺序有意如此：<b>先本地自检再续约</b>——已经过期的租约必须立刻停采，
     * 而不是先尝试续约（续约成功也不该让一个已过期的窗口继续采集）。</p>
     */
    public void renewAndSelfCheck() {
        renewAndSelfCheck(LocalDateTime.now());
    }

    /** 带时间注入的实现（测试用它模拟过期，不必真的等）。 */
    void renewAndSelfCheck(LocalDateTime now) {
        // ① 先本地自检：已过期的租约必须立刻停采（不等 business，也不先尝试续约）
        selfFenceExpiredLocally(now);
        // ② 有持有才续约（没租户时不制造空调用）
        if (holdings.isEmpty()) {
            log.debug("本地没有持有租户，跳过续约");
        } else {
            renew(now);
        }
        // ③ 周期重领：接管的执行入口——把别的节点退出后留下的「待接管」租户接过来
        refreshAssignmentsIfDue(now);
    }

    /**
     * 到点就重领（默认 60s 一次）。
     *
     * <p>不做这一步的后果：{@code acquire} 只在启动时调一次，那么别的节点退出后留下的租户会停在
     * 「待接管」而<b>永远无人接手</b>——§3.1① 的接管链路断在最后一步。
     * 契约保证 {@code acquire} 幂等（重复调用只续期、不重复分配），所以这里可以安全地周期性调用。</p>
     */
    private void refreshAssignmentsIfDue(LocalDateTime now) {
        LocalDateTime last = lastAcquireAt.get();
        if (last != null && Duration.between(last, now).toMillis() < properties.getAcquireIntervalMs()) {
            return;
        }
        lastAcquireAt.set(now);
        try {
            R<LeaseAcquireResp> resp = leaseClient.acquire(acquireRequest());
            if (resp == null || resp.getCode() != GlobalErrorCode.SUCCESS.getCode() || resp.getData() == null) {
                log.error("周期重领失败（非成功信封）：node={} code={}",
                    LogSanitizer.sanitize(properties.getNodeId()), resp == null ? "null" : resp.getCode());
                return;
            }
            List<Long> gained = applyAcquireResponse(resp.getData());
            if (!gained.isEmpty()) {
                acquiredCounter.increment(gained.size());
                log.warn("周期重领到租户（接管孤儿租户）：node={} 新增={}",
                    LogSanitizer.sanitize(properties.getNodeId()), LogSanitizer.sanitize(gained));
            }
        } catch (RuntimeException ex) {
            // 重领失败不影响已经在采的租户：记错误、下一轮再试（不静默）
            log.error("周期重领失败：node={}（已在采的租户不受影响）",
                LogSanitizer.sanitize(properties.getNodeId()), ex);
        }
    }

    /** 当前本地持有的租户（只读快照）。 */
    public Set<Long> heldTenants() {
        return Set.copyOf(holdings.keySet());
    }

    /** 注册：任何非成功信封都抛（P4 硬要求）。 */
    private void registerOrFail() {
        AccessNodeRegisterReq req = new AccessNodeRegisterReq();
        req.setAccessNode(properties.getNodeId());
        req.setMaxTenants(properties.getCapacity());
        R<Void> resp;
        try {
            resp = leaseClient.register(req);
        } catch (RuntimeException ex) {
            // 传输层失败（连接被拒/超时）也要给出可行动的消息：实测裸 Feign 异常只说
            // "Connection refused executing POST ..."，看不出这是「注册握手失败 ⇒ 不得开始采集」
            throw new IllegalStateException("access 启动失败：无法连接 business 完成节点注册（node="
                + properties.getNodeId() + "）", ex);
        }
        if (resp == null || resp.getCode() != GlobalErrorCode.SUCCESS.getCode()) {
            throw new IllegalStateException("access 启动失败：注册节点未成功（node=" + properties.getNodeId()
                + ", code=" + (resp == null ? "null" : resp.getCode())
                + ", message=" + (resp == null ? "" : resp.getMessage()) + "）");
        }
        log.info("节点注册成功：node={} capacity={}", LogSanitizer.sanitize(properties.getNodeId()),
            properties.getCapacity() == null ? "不限（单节点全量）" : properties.getCapacity());
    }

    /** 领取：失败同样抛（没有租户就不要开始采集）。 */
    private void acquireOrFail() {
        R<LeaseAcquireResp> resp;
        try {
            resp = leaseClient.acquire(acquireRequest());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("access 启动失败：无法连接 business 领取租约（node="
                + properties.getNodeId() + "）", ex);
        }
        if (resp == null || resp.getCode() != GlobalErrorCode.SUCCESS.getCode() || resp.getData() == null) {
            throw new IllegalStateException("access 启动失败：领取租约未成功（node=" + properties.getNodeId()
                + ", code=" + (resp == null ? "null" : resp.getCode()) + "）");
        }
        List<Long> tenantIds = applyAcquireResponse(resp.getData());
        log.info("租户领取完成：node={} 持有租户={}", LogSanitizer.sanitize(properties.getNodeId()),
            LogSanitizer.sanitize(tenantIds));
    }

    /** 领取请求（启动领取与周期重领共用）。 */
    private LeaseAcquireReq acquireRequest() {
        LeaseAcquireReq req = new LeaseAcquireReq();
        req.setAccessNode(properties.getNodeId());
        return req;
    }

    /**
     * 落地领取响应：写入本地快照 + 开始采集。
     *
     * @param data 领取响应
     * @return 本次<b>新增</b>（此前未持有）的租户
     */
    private List<Long> applyAcquireResponse(LeaseAcquireResp data) {
        List<Long> gained = new ArrayList<>();
        for (LeaseAssignmentDto assignment : data.getAssignments()) {
            boolean isNew = !holdings.containsKey(assignment.getTenantId());
            applyAssignment(assignment);
            if (isNew) {
                gained.add(assignment.getTenantId());
            }
        }
        return gained;
    }

    /**
     * 本地过期自检：本地到期时间已过 → 立即断链停采。
     *
     * <p>这是「business 不可达 / 续约一直失败」时唯一的兜底——也是 spec §3.1① 要求旧节点
     * <b>自己</b>停采的那条。</p>
     */
    private void selfFenceExpiredLocally(LocalDateTime now) {
        List<Long> expired = holdings.entrySet().stream()
            .filter(entry -> entry.getValue().expiredAt(now))
            .map(Map.Entry::getKey)
            .toList();
        for (Long tenantId : expired) {
            fenceLocally(tenantId, "本地租约已过期（未成功续约）");
        }
    }

    /** 续约：异常与非成功信封都「不延长到期时间」，让下一轮本地自检兜住。 */
    private void renew(LocalDateTime now) {
        LeaseRenewReq req = new LeaseRenewReq();
        req.setAccessNode(properties.getNodeId());
        req.setLeases(holdings.entrySet().stream().map(entry -> renewItem(entry.getKey(), entry.getValue()))
            .toList());
        R<LeaseRenewResp> resp;
        try {
            resp = leaseClient.renew(req);
        } catch (RuntimeException ex) {
            renewFailure.increment();
            log.error("租约续约失败（异常）：node={} 持有租户={}（本地到期时间未延长，下一轮本地自检会兜住）",
                LogSanitizer.sanitize(properties.getNodeId()), LogSanitizer.sanitize(holdings.keySet()), ex);
            return;
        }
        if (resp == null || resp.getCode() != GlobalErrorCode.SUCCESS.getCode() || resp.getData() == null) {
            renewFailure.increment();
            log.error("租约续约失败（非成功信封）：node={} code={}", LogSanitizer.sanitize(properties.getNodeId()),
                resp == null ? "null" : resp.getCode());
            return;
        }
        applyRenewResponse(resp.getData(), now);
        renewSuccess.increment();
    }

    /** 应用续约响应：节点级 fencing 优先于逐租户处理。 */
    private void applyRenewResponse(LeaseRenewResp resp, LocalDateTime now) {
        if (resp.isNodeFenced()) {
            nodeFenced.increment();
            log.warn("business 判定本节点已失效（nodeFenced）：整体停采并重新注册");
            linkManager.fenceAll("business 判定节点失效");
            holdings.clear();
            lastAcquireAt.set(now);
            try {
                registerOrFail();
                acquireOrFail();
            } catch (RuntimeException ex) {
                // 重新注册失败：保持停采状态（fenced），下一轮会再试；不静默
                log.error("重新注册/领取失败，节点保持停采：node={}",
                    LogSanitizer.sanitize(properties.getNodeId()), ex);
            }
            return;
        }
        for (LeaseRenewAck ack : resp.getRenewedLeases()) {
            holdings.put(ack.getTenantId(), new LeaseSnapshot(ack.getLeaseExpireAt(), ack.getEpoch()));
            linkManager.startCollecting(ack.getTenantId());
        }
        for (Long tenantId : resp.getRevokedTenantIds()) {
            revoked.increment();
            fenceLocally(tenantId, "business 判定该租户已失效/被接管");
        }
        log.debug("续约完成：node={} 续约={} 撤销={} 本地持有={}",
            LogSanitizer.sanitize(properties.getNodeId()), resp.getRenewedLeases().size(),
            resp.getRevokedTenantIds().size(), holdings.size());
    }

    /** 落地一条归属：写内存快照 + 开始采集。 */
    private void applyAssignment(LeaseAssignmentDto assignment) {
        holdings.put(assignment.getTenantId(),
            new LeaseSnapshot(assignment.getLeaseExpireAt(), assignment.getEpoch()));
        linkManager.startCollecting(assignment.getTenantId());
    }

    /** self-fencing 的唯一落地点：摘快照 + 断链停采。 */
    private void fenceLocally(Long tenantId, String reason) {
        holdings.remove(tenantId);
        linkManager.fence(tenantId, reason);
        selfFenced.increment();
        log.warn("租户已断链停采：tenantId={} reason={}", LogSanitizer.sanitize(tenantId),
            LogSanitizer.sanitize(reason));
    }

    /** 组装一条续约条目（带上本地 epoch，供 business 判定是否落后）。 */
    private LeaseRenewItem renewItem(Long tenantId, LeaseSnapshot snapshot) {
        LeaseRenewItem item = new LeaseRenewItem();
        item.setTenantId(tenantId);
        item.setEpoch(snapshot.epoch());
        return item;
    }
}
