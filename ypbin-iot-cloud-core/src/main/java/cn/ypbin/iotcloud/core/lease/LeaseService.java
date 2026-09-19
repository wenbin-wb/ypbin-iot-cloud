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

import cn.ypbin.iotcloud.api.lease.LeaseAcquireResp;
import cn.ypbin.iotcloud.api.lease.LeaseAssignmentDto;
import cn.ypbin.iotcloud.api.lease.LeaseEpochRules;
import cn.ypbin.iotcloud.api.lease.LeaseRenewAck;
import cn.ypbin.iotcloud.api.lease.LeaseRenewItem;
import cn.ypbin.iotcloud.api.lease.LeaseRenewResp;
import cn.ypbin.iotcloud.api.lease.LeaseState;
import cn.ypbin.iotcloud.api.lease.TenantEpochBatchResp;
import cn.ypbin.iotcloud.api.lease.TenantEpochItem;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 租约归属维护（IOT-CLOUD-SPEC.md §3.1①，部署单元② 的 business.core 侧）。
 *
 * <p>职责边界：本类只维护「哪个租户租给了哪个 access 节点、租约何时到期、台账版本号是多少」，
 * 不关心设备与连接——后者全在 access 节点本地。三条硬语义：</p>
 * <ol>
 *   <li><b>失效检测</b>（{@link #markExpired(LocalDateTime)}）：{@code lease_expire_at < now} 的租约置为
 *       {@link LeaseState#PENDING_TAKEOVER}。<b>没有这一步，「节点退出 → 待接管」永远不会被触发</b>，
 *       租户会静默离线（v3 漏掉的就是这条）；</li>
 *   <li><b>接管要递增 epoch</b>：归属换节点属于台账变更，必须递增版本号，否则旧节点拿到的快照/事件
 *       无从判断自己已被接管（§3.1②③）；正常释放<b>不</b>递增（台账没变）；</li>
 *   <li><b>self-fencing 的判据由续约响应给出</b>：见 {@link #renew(String, List)} 的
 *       {@code revokedTenantIds} / {@code nodeFenced}。business 只负责「说出来」，
 *       真正断链停采是 access 侧的行为。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-19
 */
public class LeaseService {

    private static final Logger log = LoggerFactory.getLogger(LeaseService.class);

    private final InMemoryLeaseStore store;
    private final LeaseProperties properties;

    /**
     * 构造租约维护服务。
     *
     * @param store      归属存储（M0a 内存实现）
     * @param properties 租约参数
     */
    public LeaseService(InMemoryLeaseStore store, LeaseProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    /**
     * 注册节点（幂等）。
     *
     * @param accessNode 节点标识
     * @param maxTenants 该节点最多能带多少租户（决定它能领取多少）
     */
    public void register(String accessNode, int maxTenants) {
        store.registerNode(accessNode, maxTenants);
        log.info("access 节点注册：node={} maxTenants={}", accessNode, maxTenants);
    }

    /**
     * 领取分给该节点的租户（幂等：重复调用只会续期，不会重复分配）。
     *
     * <p>分配优先级：① 本节点仍有效持有的（续期）→ ② 可接管的（待接管 / 已过期，<b>递增 epoch</b>）
     * → ③ 从未分配过或被正常释放的（用当前 epoch，不递增）。容量来自注册时的 {@code maxTenants}。</p>
     *
     * <p><b>容量收缩不回收已有租约</b>：容量只约束<b>新增</b>分配。若某节点被重新注册成更小的容量，
     * 它已持有且仍在续约的租户不会被抢走——抢走等于让设备断采，代价远大于多带几个租户；
     * 真要瘦身请先 {@link #release(String, List)} 再重领。</p>
     *
     * @param accessNode 节点标识（必须已注册）
     * @return 该节点当前的租户归属（含续期与本次新领取的）
     * @throws BusinessException 节点未注册（启动次序错了：必须先 register，§3.1⑥）
     */
    public LeaseAcquireResp acquire(String accessNode) {
        LocalDateTime now = LocalDateTime.now();
        if (!store.isRegistered(accessNode)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "节点未注册，请先调用 /internal/lease/register：node=" + accessNode);
        }
        int capacity = store.capacityOf(accessNode);
        List<LeaseAssignment> result = new ArrayList<>();
        for (LeaseAssignment assignment : store.findAll()) {
            if (assignment.heldBy(accessNode, now)) {
                result.add(saveRenewed(assignment, now));
            }
        }
        int slots = capacity - result.size();
        for (LeaseAssignment assignment : store.findAll()) {
            if (slots <= 0) {
                break;
            }
            if (assignment.takeoverable(now)) {
                result.add(takeOver(assignment, accessNode, now));
                slots--;
            }
        }
        for (Long tenantId : claimableTenantIds()) {
            if (slots <= 0) {
                break;
            }
            result.add(assignNew(tenantId, accessNode, now));
            slots--;
        }
        LeaseAcquireResp resp = new LeaseAcquireResp();
        resp.setAccessNode(accessNode);
        resp.setAssignments(result.stream().map(LeaseAssignment::toDto).toList());
        log.info("租户领取完成：node={} capacity={} 本次归属={}", accessNode, capacity, result.size());
        return resp;
    }

    /**
     * 续约（access 周期调用，默认 10s），并给出 self-fencing 判据。
     *
     * <p>逐租户判定「是否仍由该节点有效持有」：不持有的进 {@code revokedTenantIds}
     * （已被接管/已释放/记录不存在）——节点必须对它们断链停采。节点本身没注册时整体
     * {@code nodeFenced = true}（注册丢失或已被清出），节点必须整体停采后重新注册。</p>
     *
     * @param accessNode 节点标识
     * @param leases     节点自认为持有的租户及其本地 epoch
     * @return 逐租户回执 + 被撤销租户 + 节点级 fencing 信号
     */
    public LeaseRenewResp renew(String accessNode, List<LeaseRenewItem> leases) {
        LocalDateTime now = LocalDateTime.now();
        LeaseRenewResp resp = new LeaseRenewResp();
        if (!store.isRegistered(accessNode)) {
            resp.setNodeFenced(true);
            resp.setRevokedTenantIds(leases.stream().map(LeaseRenewItem::getTenantId).toList());
            log.warn("续约的节点未注册，已整体 fencing：node={} 涉及租户数={}", accessNode, leases.size());
            return resp;
        }
        List<LeaseRenewAck> acks = new ArrayList<>();
        List<Long> revoked = new ArrayList<>();
        for (LeaseRenewItem item : leases) {
            Optional<LeaseAssignment> renewed = store.find(item.getTenantId())
                .filter(assignment -> assignment.heldBy(accessNode, now))
                .map(assignment -> saveRenewed(assignment, now));
            if (renewed.isPresent()) {
                acks.add(toAck(renewed.get()));
            } else {
                revoked.add(item.getTenantId());
            }
        }
        resp.setRenewedLeases(acks);
        resp.setRevokedTenantIds(revoked);
        log.debug("续约完成：node={} 续约={} 撤销={}", accessNode, acks.size(), revoked.size());
        return resp;
    }

    /**
     * 释放租约（节点正常下线）。
     *
     * <p>只释放该节点<b>当前有效持有</b>的租户；不是它持有的（例如已被接管）只记 WARN 并忽略——
     * 一个慢节点的释放请求不该把别人正在用的租约抢走。</p>
     *
     * @param accessNode 节点标识
     * @param tenantIds  要释放的租户
     */
    public void release(String accessNode, List<Long> tenantIds) {
        LocalDateTime now = LocalDateTime.now();
        int released = 0;
        for (Long tenantId : tenantIds) {
            Optional<LeaseAssignment> held = store.find(tenantId)
                .filter(assignment -> assignment.heldBy(accessNode, now));
            if (held.isPresent()) {
                store.save(held.get().asReleased());
                released++;
            } else {
                log.warn("忽略了不属于该节点的释放请求：node={} tenantId={}", accessNode, tenantId);
            }
        }
        log.info("租约释放完成：node={} 请求={} 实际释放={}", accessNode, tenantIds.size(), released);
    }

    /**
     * 查询某个租户的当前归属。
     *
     * @param tenantId 租户 ID
     * @return 归属信息；<b>从未分配过</b>时为空（对外的语义是「当前无归属」）
     */
    public Optional<LeaseAssignmentDto> queryAssignment(Long tenantId) {
        return store.find(tenantId).map(LeaseAssignment::toDto);
    }

    /**
     * 批量查询全部租户的台账版本号（access 的周期对账用，一次拉全量而不是每租户一次调用）。
     *
     * <p>判据必须只用 epoch：设备数在「改参数 / 删一台又加一台」时不变，用数量对账会产生假阴性（§3.1③）。</p>
     *
     * @return 全部已知租户的 epoch + 读取时刻
     */
    public TenantEpochBatchResp batchEpoch() {
        Set<Long> tenantIds = new TreeSet<>(properties.getAssignableTenantIds());
        tenantIds.addAll(store.knownTenantIds());
        List<TenantEpochItem> items = new ArrayList<>();
        for (Long tenantId : tenantIds) {
            TenantEpochItem item = new TenantEpochItem();
            item.setTenantId(tenantId);
            item.setEpoch(store.currentEpoch(tenantId));
            items.add(item);
        }
        TenantEpochBatchResp resp = new TenantEpochBatchResp();
        resp.setItems(items);
        resp.setReadAt(LocalDateTime.now());
        return resp;
    }

    /**
     * 失效检测：把「仍标记有效但已过期」的租约置为 {@link LeaseState#PENDING_TAKEOVER}（待接管）。
     *
     * <p>这就是 v3 缺失的那条：没有它，「节点退出 → 待接管」永远不会被触发。
     * 只改状态、<b>不</b>递增 epoch（真正的台账变更发生在接管那一刻）。</p>
     *
     * @param now 当前时刻（由调用方注入，便于测试与统一时间基准）
     * @return 本次被置为待接管的租户（它们的原持有节点）
     */
    public List<LeaseAssignmentDto> markExpired(LocalDateTime now) {
        List<LeaseAssignmentDto> marked = new ArrayList<>();
        for (LeaseAssignment assignment : store.findAll()) {
            if (assignment.state() == LeaseState.ACTIVE && LeaseEpochRules.isLeaseExpired(assignment.leaseExpireAt(), now)) {
                LeaseAssignment pending = assignment.asPendingTakeover();
                store.save(pending);
                marked.add(pending.toDto());
                log.warn("租约已过期，租户置为待接管：tenantId={} 原节点={} 到期时间={}",
                    assignment.tenantId(), assignment.accessNode(), assignment.leaseExpireAt());
            }
        }
        return marked;
    }

    /** 续期：保持 epoch 不变，只把到期时间往后推一个 TTL。 */
    private LeaseAssignment saveRenewed(LeaseAssignment assignment, LocalDateTime now) {
        LeaseAssignment renewed = assignment.withLease(assignment.accessNode(),
            now.plus(properties.getTtl()), assignment.epoch());
        store.save(renewed);
        return renewed;
    }

    /** 接管：归属换节点，**先递增 epoch 再写归属**（§3.1③ 的同一事务语义）。 */
    private LeaseAssignment takeOver(LeaseAssignment assignment, String accessNode, LocalDateTime now) {
        long epoch = store.nextEpoch(assignment.tenantId());
        LeaseAssignment taken = assignment.withLease(accessNode, now.plus(properties.getTtl()), epoch);
        store.save(taken);
        log.warn("租户接管：tenantId={} 原节点={} 新节点={} 新 epoch={}",
            assignment.tenantId(), assignment.accessNode(), accessNode, epoch);
        return taken;
    }

    /** 首次分配：用当前 epoch（不递增——台账没有变化）。 */
    private LeaseAssignment assignNew(Long tenantId, String accessNode, LocalDateTime now) {
        LeaseAssignment assignment = new LeaseAssignment(tenantId, accessNode, now.plus(properties.getTtl()),
            store.currentEpoch(tenantId), LeaseState.ACTIVE);
        store.save(assignment);
        log.info("租户首次分配：tenantId={} node={} epoch={}", tenantId, accessNode, assignment.epoch());
        return assignment;
    }

    /** 可做「首次分配」的租户：配置里给了、且没有归属记录或已被正常释放。 */
    private List<Long> claimableTenantIds() {
        return properties.getAssignableTenantIds().stream()
            .filter(tenantId -> store.find(tenantId).map(LeaseAssignment::released).orElse(true))
            .toList();
    }

    /** 构造逐租户续约回执。 */
    private LeaseRenewAck toAck(LeaseAssignment assignment) {
        LeaseRenewAck ack = new LeaseRenewAck();
        ack.setTenantId(assignment.tenantId());
        ack.setLeaseExpireAt(assignment.leaseExpireAt());
        ack.setEpoch(assignment.epoch());
        return ack;
    }
}
