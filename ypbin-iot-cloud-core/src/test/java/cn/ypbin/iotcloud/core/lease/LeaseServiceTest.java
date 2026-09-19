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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.iotcloud.api.lease.LeaseAcquireResp;
import cn.ypbin.iotcloud.api.lease.LeaseAssignmentDto;
import cn.ypbin.iotcloud.api.lease.LeaseRenewItem;
import cn.ypbin.iotcloud.api.lease.LeaseRenewResp;
import cn.ypbin.iotcloud.api.lease.LeaseState;
import cn.ypbin.iotcloud.api.lease.TenantEpochBatchResp;
import cn.ypbin.starter.core.exception.BusinessException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 租约维护的行为测试（IOT-CLOUD-SPEC.md §3.1①）。
 *
 * <p>覆盖四类不变量：<b>分配容量</b>、<b>失效检测（过期 → 待接管）</b>、
 * <b>接管必须递增 epoch 而正常释放不递增</b>、<b>续约响应是 self-fencing 的判据来源</b>。</p>
 *
 * <p>时间不进注入：{@code acquire}/{@code renew} 用真实 now，只有「制造过期」的场景直接往
 * store 里写一条已过期的归属（比注入时钟更贴近数据实际形态）。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
class LeaseServiceTest {

    private static final long TENANT_A = 11L;
    private static final long TENANT_B = 22L;
    private static final String NODE_1 = "access-1";
    private static final String NODE_2 = "access-2";
    private static final String NODE_3 = "access-3";

    private InMemoryLeaseStore store;
    private LeaseService service;

    @BeforeEach
    void setUp() {
        LeaseProperties properties = new LeaseProperties();
        properties.setTtl(Duration.ofSeconds(30));
        properties.setAssignableTenantIds(List.of(TENANT_A, TENANT_B));
        store = new InMemoryLeaseStore();
        service = new LeaseService(store, properties);
    }

    @Test
    @DisplayName("领取：按容量分配可分配租户，重复领取只续期不重复分配")
    void acquireShouldFillUpToCapacityAndBeIdempotent() {
        service.register(NODE_1, 2);

        LeaseAcquireResp first = service.acquire(NODE_1);

        assertThat(first.getAccessNode()).isEqualTo(NODE_1);
        assertThat(tenantIdsOf(first)).containsExactly(TENANT_A, TENANT_B);
        assertThat(first.getAssignments()).allSatisfy(
            assignment -> assertThat(assignment.getState()).isEqualTo(LeaseState.ACTIVE));

        LeaseAcquireResp second = service.acquire(NODE_1);

        assertThat(tenantIdsOf(second)).containsExactly(TENANT_A, TENANT_B);
    }

    @Test
    @DisplayName("领取：容量小于可分配数时只领到容量那么多")
    void acquireShouldRespectCapacity() {
        service.register(NODE_1, 1);

        LeaseAcquireResp resp = service.acquire(NODE_1);

        assertThat(tenantIdsOf(resp)).containsExactly(TENANT_A);
    }

    @Test
    @DisplayName("容量收缩：只约束新增分配，不抢走已持有且仍在续约的租户（抢走=设备断采）")
    void acquireShouldKeepHeldTenantsWhenCapacityShrinks() {
        service.register(NODE_1, 2);
        service.acquire(NODE_1);

        service.register(NODE_1, 0);
        LeaseAcquireResp resp = service.acquire(NODE_1);

        assertThat(tenantIdsOf(resp)).containsExactly(TENANT_A, TENANT_B);
    }

    @Test
    @DisplayName("领取：未注册的节点必须显式报错（启动次序错了，不能静默返回空）")
    void acquireShouldRejectUnregisteredNode() {
        assertThatThrownBy(() -> service.acquire("ghost"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("节点未注册");
    }

    @Test
    @DisplayName("失效检测：过期租约被置为待接管；未过期的不动")
    void markExpiredShouldMarkOnlyExpiredLeases() {
        service.register(NODE_1, 2);
        service.acquire(NODE_1);
        LocalDateTime now = LocalDateTime.now();
        // 把其中一个租户改成「已过期但状态仍是 ACTIVE」——这正是旧节点假死时的数据形态
        LeaseAssignment expired = store.find(TENANT_A).orElseThrow();
        store.save(new LeaseAssignment(expired.tenantId(), expired.accessNode(),
            now.minusSeconds(1), expired.epoch(), LeaseState.ACTIVE));

        List<LeaseAssignmentDto> marked = service.markExpired(now);

        assertThat(marked).extracting(LeaseAssignmentDto::getTenantId).containsExactly(TENANT_A);
        assertThat(store.find(TENANT_A).orElseThrow().state()).isEqualTo(LeaseState.PENDING_TAKEOVER);
        assertThat(store.find(TENANT_B).orElseThrow().state()).isEqualTo(LeaseState.ACTIVE);
        // 幂等：再扫一次不会重复上报
        assertThat(service.markExpired(now)).isEmpty();
    }

    @Test
    @DisplayName("接管：新节点领走待接管租户时必须递增 epoch（否则旧节点看不出已被接管）")
    void takeoverShouldBumpEpoch() {
        service.register(NODE_1, 2);
        service.acquire(NODE_1);
        long epochBefore = store.currentEpoch(TENANT_A);
        LocalDateTime now = LocalDateTime.now();
        service.markExpired(now.plusSeconds(120));

        service.register(NODE_2, 1);
        LeaseAcquireResp resp = service.acquire(NODE_2);

        assertThat(tenantIdsOf(resp)).containsExactly(TENANT_A);
        assertThat(store.currentEpoch(TENANT_A)).isEqualTo(epochBefore + 1);
        assertThat(resp.getAssignments().get(0).getAccessNode()).isEqualTo(NODE_2);
    }

    @Test
    @DisplayName("接管：已过期但仍标记 ACTIVE 的租约也可被领走（旧节点可能已死，扫描还没跑到）")
    void acquireShouldTakeOverExpiredActiveLease() {
        service.register(NODE_1, 2);
        service.acquire(NODE_1);
        LeaseAssignment held = store.find(TENANT_A).orElseThrow();
        store.save(new LeaseAssignment(held.tenantId(), held.accessNode(),
            LocalDateTime.now().minusSeconds(1), held.epoch(), LeaseState.ACTIVE));
        long epochBefore = store.currentEpoch(TENANT_A);

        service.register(NODE_2, 1);
        LeaseAcquireResp resp = service.acquire(NODE_2);

        assertThat(tenantIdsOf(resp)).containsExactly(TENANT_A);
        assertThat(store.currentEpoch(TENANT_A)).isEqualTo(epochBefore + 1);
    }

    @Test
    @DisplayName("释放：正常下线后租约可被别的节点重新分配，且**不**递增 epoch")
    void releaseShouldFreeTenantWithoutBumpingEpoch() {
        service.register(NODE_1, 1);
        service.acquire(NODE_1);
        long epochBefore = store.currentEpoch(TENANT_A);

        service.release(NODE_1, List.of(TENANT_A));

        assertThat(store.find(TENANT_A).orElseThrow().state()).isEqualTo(LeaseState.RELEASED);
        service.register(NODE_2, 1);

        LeaseAcquireResp resp = service.acquire(NODE_2);

        assertThat(tenantIdsOf(resp)).containsExactly(TENANT_A);
        assertThat(store.currentEpoch(TENANT_A)).isEqualTo(epochBefore);
    }

    @Test
    @DisplayName("释放：不是自己持有的租户，忽略而不是抢走（慢节点不该影响别人）")
    void releaseShouldIgnoreTenantsNotHeldByNode() {
        service.register(NODE_1, 1);
        service.acquire(NODE_1);

        service.release(NODE_2, List.of(TENANT_A));

        assertThat(store.find(TENANT_A).orElseThrow().state()).isEqualTo(LeaseState.ACTIVE);
        assertThat(store.find(TENANT_A).orElseThrow().accessNode()).isEqualTo(NODE_1);
    }

    @Test
    @DisplayName("续约：逐租户回执带新到期时间与 epoch")
    void renewShouldAckEachHeldTenant() {
        service.register(NODE_1, 2);
        service.acquire(NODE_1);
        LeaseAssignment before = store.find(TENANT_A).orElseThrow();

        LeaseRenewResp resp = service.renew(NODE_1, List.of(renewItem(TENANT_A, before.epoch()),
            renewItem(TENANT_B, store.currentEpoch(TENANT_B))));

        assertThat(resp.getRenewedLeases()).hasSize(2);
        assertThat(resp.getRevokedTenantIds()).isEmpty();
        assertThat(resp.isNodeFenced()).isFalse();
        assertThat(resp.getRenewedLeases()).allSatisfy(
            ack -> assertThat(ack.getLeaseExpireAt()).isAfter(LocalDateTime.now()));
    }

    @Test
    @DisplayName("续约：节点未注册 → 整体 fencing，且请求里的租户全部撤销")
    void renewShouldFenceUnregisteredNode() {
        LeaseRenewResp resp = service.renew("ghost", List.of(renewItem(TENANT_A, 1L)));

        assertThat(resp.isNodeFenced()).isTrue();
        assertThat(resp.getRevokedTenantIds()).containsExactly(TENANT_A);
        assertThat(resp.getRenewedLeases()).isEmpty();
    }

    @Test
    @DisplayName("续约：租户已被别的节点接管 → 进撤销列表（节点必须断链停采）")
    void renewShouldRevokeTenantTakenOverByAnotherNode() {
        service.register(NODE_1, 1);
        service.acquire(NODE_1);
        long epoch = store.currentEpoch(TENANT_A);
        service.markExpired(LocalDateTime.now().plusSeconds(120));
        service.register(NODE_2, 1);
        service.acquire(NODE_2);

        LeaseRenewResp resp = service.renew(NODE_1, List.of(renewItem(TENANT_A, epoch)));

        assertThat(resp.getRevokedTenantIds()).containsExactly(TENANT_A);
        assertThat(resp.getRenewedLeases()).isEmpty();
        assertThat(resp.isNodeFenced()).isFalse();
    }

    @Test
    @DisplayName("查询归属：未知租户返回空，已知租户字段完整")
    void queryAssignmentShouldReturnEmptyForUnknownTenant() {
        assertThat(service.queryAssignment(999L)).isEmpty();

        service.register(NODE_1, 1);
        service.acquire(NODE_1);

        Optional<LeaseAssignmentDto> found = service.queryAssignment(TENANT_A);
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().getAccessNode()).isEqualTo(NODE_1);
        assertThat(found.orElseThrow().getState()).isEqualTo(LeaseState.ACTIVE);
        assertThat(found.orElseThrow().getEpoch()).isEqualTo(store.currentEpoch(TENANT_A));
    }

    @Test
    @DisplayName("批量 epoch：覆盖「配置里的 + 已知的」租户，按 ID 升序并带读取时刻")
    void batchEpochShouldCoverConfiguredAndKnownTenants() {
        service.register(NODE_1, 1);
        service.acquire(NODE_1);

        TenantEpochBatchResp resp = service.batchEpoch();

        assertThat(resp.getReadAt()).isNotNull();
        assertThat(resp.getItems()).extracting(item -> item.getTenantId())
            .containsExactly(TENANT_A, TENANT_B);
        assertThat(resp.getItems()).allSatisfy(item -> assertThat(item.getEpoch()).isPositive());
    }

    @Test
    @DisplayName("内部状态：heldBy/takeoverable/released 的组合判据（只判状态会漏判已过期）")
    void assignmentShouldCombineStateAndExpiry() {
        LocalDateTime now = LocalDateTime.now();
        LeaseAssignment active = new LeaseAssignment(TENANT_A, NODE_1, now.plusSeconds(30), 1L, LeaseState.ACTIVE);
        LeaseAssignment expired = new LeaseAssignment(TENANT_A, NODE_1, now.minusSeconds(1), 1L, LeaseState.ACTIVE);
        LeaseAssignment pending = active.asPendingTakeover();
        LeaseAssignment released = active.asReleased();

        assertThat(active.heldBy(NODE_1, now)).isTrue();
        assertThat(active.heldBy(NODE_2, now)).isFalse();
        assertThat(expired.heldBy(NODE_1, now)).isFalse();
        assertThat(expired.takeoverable(now)).isTrue();
        assertThat(active.takeoverable(now)).isFalse();
        assertThat(pending.takeoverable(now)).isTrue();
        assertThat(released.released()).isTrue();
        assertThat(released.takeoverable(now)).isFalse();
        assertThat(active.withLease(NODE_2, now.plusSeconds(60), 9L))
            .isEqualTo(new LeaseAssignment(TENANT_A, NODE_2, now.plusSeconds(60), 9L, LeaseState.ACTIVE));
    }

    @Test
    @DisplayName("容量不限（maxTenants 为空）：单节点全量模式可领取全部可分配租户")
    void unlimitedCapacityShouldAssignAllTenants() {
        service.register(NODE_1, null);

        LeaseAcquireResp resp = service.acquire(NODE_1);

        assertThat(tenantIdsOf(resp)).containsExactly(TENANT_A, TENANT_B);
    }

    @Test
    @DisplayName("并发领取：同一租户不会被同时分给两个节点（单副本也必须进程内互斥）")
    void concurrentAcquireMustNotDoubleAssign() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 200; round++) {
                // 每轮把租户重置为「已释放」（可被重新分配），只观察并发领取本身
                store.save(new LeaseAssignment(TENANT_A, NODE_1, LocalDateTime.now().plusSeconds(30),
                    store.currentEpoch(TENANT_A), LeaseState.RELEASED));
                service.register(NODE_2, 1);
                service.register(NODE_3, 1);
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Boolean> second = pool.submit(acquiredBy(NODE_2, barrier));
                Future<Boolean> third = pool.submit(acquiredBy(NODE_3, barrier));
                long owners = (second.get() ? 1 : 0) + (third.get() ? 1 : 0);
                assertThat(owners).as("第 %s 轮：同一租户只允许一个节点持有（双主=两台设备同时轮询）", round)
                    .isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("续约：租户已被正常释放 → 进撤销列表（节点必须停采）")
    void renewShouldRevokeReleasedTenant() {
        service.register(NODE_1, 1);
        service.acquire(NODE_1);
        long epoch = store.currentEpoch(TENANT_A);
        service.release(NODE_1, List.of(TENANT_A));

        LeaseRenewResp resp = service.renew(NODE_1, List.of(renewItem(TENANT_A, epoch)));

        assertThat(resp.getRevokedTenantIds()).containsExactly(TENANT_A);
        assertThat(resp.getRenewedLeases()).isEmpty();
    }

    @Test
    @DisplayName("续约：租户从未分配过 → 进撤销列表（节点自认为持有但系统里没有）")
    void renewShouldRevokeUnknownTenant() {
        service.register(NODE_1, 1);

        LeaseRenewResp resp = service.renew(NODE_1, List.of(renewItem(999L, 1L)));

        assertThat(resp.getRevokedTenantIds()).containsExactly(999L);
        assertThat(resp.isNodeFenced()).isFalse();
    }

    @Test
    @DisplayName("续约：节点自己漏续约导致租约过期（进程可能还活着）→ 进撤销列表，要求 self-fencing")
    void renewShouldRevokeTenantWhoseLeaseExpired() {
        service.register(NODE_1, 1);
        service.acquire(NODE_1);
        LeaseAssignment held = store.find(TENANT_A).orElseThrow();
        // 模拟「节点漏了一轮续约」：状态仍是 ACTIVE，但本地已过期
        store.save(new LeaseAssignment(TENANT_A, held.accessNode(), LocalDateTime.now().minusSeconds(1),
            held.epoch(), LeaseState.ACTIVE));

        LeaseRenewResp resp = service.renew(NODE_1, List.of(renewItem(TENANT_A, held.epoch())));

        assertThat(resp.getRevokedTenantIds()).containsExactly(TENANT_A);
        assertThat(resp.isNodeFenced()).isFalse();
    }

    private Callable<Boolean> acquiredBy(String accessNode, CyclicBarrier barrier) {
        return () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.acquire(accessNode).getAssignments().stream()
                .anyMatch(assignment -> assignment.getTenantId().equals(TENANT_A)
                    && assignment.getState() == LeaseState.ACTIVE);
        };
    }

    private List<Long> tenantIdsOf(LeaseAcquireResp resp) {
        return resp.getAssignments().stream().map(LeaseAssignmentDto::getTenantId).sorted().toList();
    }

    private LeaseRenewItem renewItem(Long tenantId, long epoch) {
        LeaseRenewItem item = new LeaseRenewItem();
        item.setTenantId(tenantId);
        item.setEpoch(epoch);
        return item;
    }
}
