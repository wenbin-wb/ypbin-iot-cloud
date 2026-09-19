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

import cn.ypbin.iotcloud.api.lease.LeaseEpochRules;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租约归属的<b>内存实现</b>（M0a）。
 *
 * <p>⚠️ M0a 还没有台账表（§12.2 的迁移在 M0b 定稿），所以归属状态暂时放在进程内存里：
 * 它让「注册 → 领取 → 续约 → 失效扫描 → 接管」这条链能在单进程内被完整验证，
 * 但**多副本 business 之间不共享**。M0b 换成 {@code tenant_node_assignment} 表时，
 * 失效扫描必须改成「单写者 / 原子 UPDATE」形态（见 ADR-0001），否则 N 个副本会同时判定同一批租约。</p>
 *
 * <p>线程安全：内部三个 Map 都是 {@link ConcurrentHashMap}；但「读-改-写」的组合操作
 * （如 {@link LeaseService#acquire(String)}）**不是原子的**——M0a 单副本够用，
 * M0b 换表后由数据库事务保证。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
public class InMemoryLeaseStore {

    /** 首次出现在系统里的租户的台账版本号。 */
    static final long INITIAL_EPOCH = 1L;

    private final Map<Long, LeaseAssignment> assignments = new ConcurrentHashMap<>();
    private final Map<String, Integer> nodeCapacities = new ConcurrentHashMap<>();
    private final Map<Long, Long> tenantEpochs = new ConcurrentHashMap<>();

    /** 注册（或刷新）节点容量；幂等。 */
    public void registerNode(String accessNode, int maxTenants) {
        nodeCapacities.put(accessNode, maxTenants);
    }

    /** 节点是否已注册。 */
    public boolean isRegistered(String accessNode) {
        return nodeCapacities.containsKey(accessNode);
    }

    /** 节点容量；未注册返回 0。 */
    public int capacityOf(String accessNode) {
        return nodeCapacities.getOrDefault(accessNode, 0);
    }

    /** 查某个租户的归属。 */
    public Optional<LeaseAssignment> find(Long tenantId) {
        return Optional.ofNullable(assignments.get(tenantId));
    }

    /** 全部归属（按租户 ID 排序：日志与断言都要求确定性顺序）。 */
    public List<LeaseAssignment> findAll() {
        return assignments.values().stream()
            .sorted(Comparator.comparing(LeaseAssignment::tenantId))
            .toList();
    }

    /** 保存归属，并同步台账版本号。 */
    public void save(LeaseAssignment assignment) {
        assignments.put(assignment.tenantId(), assignment);
        tenantEpochs.put(assignment.tenantId(), assignment.epoch());
    }

    /** 当前台账版本号；未知租户返回 {@link #INITIAL_EPOCH}。 */
    public long currentEpoch(Long tenantId) {
        return tenantEpochs.getOrDefault(tenantId, INITIAL_EPOCH);
    }

    /**
     * 递增并返回台账版本号。
     *
     * <p>§3.1③ 要求「台账变更必须在<b>同一个事务</b>里递增 epoch」，否则会出现「变更成功但 epoch 没涨」，
     * 让周期对账**永远看不出差异**。M0a 没有事务，所以顺序上把它紧挨着归属写入调用（见
     * {@link LeaseService} 的接管分支）；M0b 换表后二者进同一事务。</p>
     */
    public long nextEpoch(Long tenantId) {
        long next = LeaseEpochRules.nextEpoch(currentEpoch(tenantId));
        tenantEpochs.put(tenantId, next);
        return next;
    }

    /** 已知租户集合（出现过归属或版本号的）。 */
    public Set<Long> knownTenantIds() {
        return Set.copyOf(tenantEpochs.keySet());
    }
}
