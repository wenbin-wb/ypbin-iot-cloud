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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.iotcloud.access.config.AccessProperties;
import cn.ypbin.iotcloud.access.link.LoggingTenantLinkManager;
import cn.ypbin.iotcloud.access.link.TenantLinkManager;
import cn.ypbin.iotcloud.api.lease.AccessNodeRegisterReq;
import cn.ypbin.iotcloud.api.lease.ILeaseClient;
import cn.ypbin.iotcloud.api.lease.LeaseAcquireResp;
import cn.ypbin.iotcloud.api.lease.LeaseAssignmentDto;
import cn.ypbin.iotcloud.api.lease.LeaseRenewAck;
import cn.ypbin.iotcloud.api.lease.LeaseRenewItem;
import cn.ypbin.iotcloud.api.lease.LeaseRenewReq;
import cn.ypbin.iotcloud.api.lease.LeaseRenewResp;
import cn.ypbin.iotcloud.api.lease.LeaseState;
import cn.ypbin.starter.core.model.R;
import feign.RetryableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 租约状态机测试（P4 的核心：注册/领取/续约 + self-fencing）。
 *
 * <p>覆盖三类必须成立的行为：</p>
 * <ol>
 *   <li><b>注册失败即启动失败</b>（契约 §6 的 P4 硬要求，含「租约维护被关闭 ⇒ 404」这种形态）；</li>
 *   <li><b>续约响应驱动停采</b>：{@code revokedTenantIds} 与 {@code nodeFenced} 两种粒度；</li>
 *   <li><b>本地过期自检不等 business</b>：续约失败/异常导致本地到期时间没被延长时，节点自己停采
 *       （spec §3.1① 要求旧节点自我 fencing 的那条）。</li>
 * </ol>
 *
 * <p>时间通过包内重载 {@code renewAndSelfCheck(now)} 注入，测试不需要真的等 30 秒。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
class AccessLeaseManagerTest {

    private static final long TENANT_A = 11L;
    private static final long TENANT_B = 22L;
    private static final String NODE = "access-test";

    private ILeaseClient leaseClient;
    private TenantLinkManager linkManager;
    private AccessProperties properties;
    private SimpleMeterRegistry meterRegistry;
    private AccessLeaseManager manager;

    @BeforeEach
    void setUp() {
        leaseClient = mock(ILeaseClient.class);
        linkManager = new LoggingTenantLinkManager();
        properties = new AccessProperties();
        properties.setNodeId(NODE);
        // 默认「永不重领」：本类里绝大多数用例只关心续约/过期自检，重领会在过期用例里把租户又领回来，
        // 造成与用例意图无关的串扰。重领本身由下面三条专门用例覆盖（它们自己把间隔调小）。
        properties.setAcquireIntervalMs(Long.MAX_VALUE);
        meterRegistry = new SimpleMeterRegistry();
        manager = new AccessLeaseManager(leaseClient, linkManager, properties, meterRegistry);
    }

    @Test
    @DisplayName("启动握手：注册 + 领取成功后，本地持有租户并开始采集")
    void startShouldRegisterAcquireAndCollect() {
        when(leaseClient.register(any())).thenReturn(R.ok());
        when(leaseClient.acquire(any())).thenReturn(R.ok(acquireResp(assignment(TENANT_A), assignment(TENANT_B))));

        manager.start();

        assertThat(manager.heldTenants()).containsExactlyInAnyOrder(TENANT_A, TENANT_B);
        assertThat(linkManager.collectingTenants()).containsExactlyInAnyOrder(TENANT_A, TENANT_B);
        assertThat(meterRegistry.get("iotcloud.lease.held").gauge().value()).isEqualTo(2.0d);
        // 注册请求必须带上本节点标识与容量（容量为空 = 不限）
        ArgumentCaptor<AccessNodeRegisterReq> captor = ArgumentCaptor.forClass(AccessNodeRegisterReq.class);
        verify(leaseClient).register(captor.capture());
        assertThat(captor.getValue().getAccessNode()).isEqualTo(NODE);
        assertThat(captor.getValue().getMaxTenants()).isNull();
    }

    @Test
    @DisplayName("注册非成功信封（含 404：租约维护被关闭）→ 启动失败，不开始采集")
    void startShouldFailFastWhenRegisterRejected() {
        when(leaseClient.register(any())).thenReturn(R.fail(404, "接口不存在"));

        assertThatThrownBy(() -> manager.start())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("注册节点未成功")
            .hasMessageContaining("404");
        assertThat(linkManager.collectingTenants()).isEmpty();
    }

    @Test
    @DisplayName("注册返回 null（契约方异常形态）→ 同样启动失败")
    void startShouldFailFastWhenRegisterReturnsNull() {
        when(leaseClient.register(any())).thenReturn(null);

        assertThatThrownBy(() -> manager.start()).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("code=null");
    }

    @Test
    @DisplayName("注册时连接类异常（RetryableException）→ 消息说「无法连接」，且带节点名")
    void startShouldSayUnreachableForRetryableException() {
        when(leaseClient.register(any())).thenThrow(mock(RetryableException.class));

        assertThatThrownBy(() -> manager.start())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("无法连接 business 完成节点注册")
            .hasMessageContaining(NODE);
    }

    @Test
    @DisplayName("注册时非连接类异常（如解码失败）→ 不能说「无法连接」，要带上异常类型（复核 D7）")
    void startShouldNotBlameConnectivityForNonTransportFailure() {
        when(leaseClient.register(any()))
            .thenThrow(new IllegalStateException("Cannot deserialize value of type LocalDateTime"));

        assertThatThrownBy(() -> manager.start())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("调用 business 完成节点注册 失败")
            .hasMessageContaining("IllegalStateException")
            .hasMessageContaining("不一定是连接问题");
    }

    @Test
    @DisplayName("领取时连接类异常 → 启动失败，消息说「无法连接」")
    void acquireShouldFailWithUnreachableMessageForRetryableException() {
        when(leaseClient.register(any())).thenReturn(R.ok());
        when(leaseClient.acquire(any())).thenThrow(mock(RetryableException.class));

        assertThatThrownBy(() -> manager.start())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("无法连接 business 领取租约");
    }

    @Test
    @DisplayName("领取非成功信封 → 启动失败")
    void startShouldFailFastWhenAcquireRejected() {
        when(leaseClient.register(any())).thenReturn(R.ok());
        when(leaseClient.acquire(any())).thenReturn(R.fail(500, "内部错误"));

        assertThatThrownBy(() -> manager.start()).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("领取租约未成功");
    }

    @Test
    @DisplayName("续约成功：用回执刷新本地到期时间与 epoch（逐租户回执的用途）")
    void renewShouldRefreshLocalExpiry() {
        startWith(TENANT_A);
        LocalDateTime newExpiry = LocalDateTime.now().plusSeconds(60);
        when(leaseClient.renew(any())).thenReturn(R.ok(renewResp(List.of(ack(TENANT_A, newExpiry, 3L)))));

        manager.renewAndSelfCheck();

        ArgumentCaptor<LeaseRenewReq> captor = ArgumentCaptor.forClass(LeaseRenewReq.class);
        verify(leaseClient).renew(captor.capture());
        assertThat(captor.getValue().getAccessNode()).isEqualTo(NODE);
        assertThat(captor.getValue().getLeases()).extracting(LeaseRenewItem::getTenantId)
            .containsExactly(TENANT_A);
        assertThat(manager.heldTenants()).containsExactly(TENANT_A);
        assertThat(meterRegistry.get("iotcloud.lease.renew.success").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("续约回执里 epoch 前进：本地快照跟着更新（供后续事件/快照准入判据使用）")
    void renewShouldAdoptAckEpoch() {
        startWith(TENANT_A);
        when(leaseClient.renew(any()))
            .thenReturn(R.ok(renewResp(List.of(ack(TENANT_A, LocalDateTime.now().plusSeconds(60), 9L)))));

        // 第一次续约：带出的 epoch 是领取时的 1，回执把本地快照推进到 9
        manager.renewAndSelfCheck();
        // 第二次续约：必须先带出 9——否则说明回执里的 epoch 没被采纳
        manager.renewAndSelfCheck();

        ArgumentCaptor<LeaseRenewReq> captor = ArgumentCaptor.forClass(LeaseRenewReq.class);
        verify(leaseClient, times(2)).renew(captor.capture());
        assertThat(captor.getAllValues().get(0).getLeases().get(0).getEpoch()).isEqualTo(1L);
        assertThat(captor.getAllValues().get(1).getLeases().get(0).getEpoch()).isEqualTo(9L);
    }

    @Test
    @DisplayName("续约响应 revokedTenantIds → 对应租户断链停采（其余照常）")
    void renewShouldFenceRevokedTenants() {
        startWith(TENANT_A, TENANT_B);
        when(leaseClient.renew(any())).thenReturn(R.ok(renewResp(List.of(
            ack(TENANT_A, LocalDateTime.now().plusSeconds(60), 1L)), List.of(TENANT_B))));

        manager.renewAndSelfCheck();

        assertThat(manager.heldTenants()).containsExactly(TENANT_A);
        assertThat(linkManager.isCollecting(TENANT_B)).isFalse();
        assertThat(linkManager.isCollecting(TENANT_A)).isTrue();
        assertThat(meterRegistry.get("iotcloud.lease.revoked").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("续约响应 nodeFenced → 整体停采后重新注册并重新领取（节点级失效不是永久的）")
    void renewShouldRecoverFromNodeFence() {
        startWith(TENANT_A);
        LeaseRenewResp fenced = new LeaseRenewResp();
        fenced.setNodeFenced(true);
        fenced.setRevokedTenantIds(List.of(TENANT_A));
        when(leaseClient.renew(any())).thenReturn(R.ok(fenced));
        when(leaseClient.register(any())).thenReturn(R.ok());
        when(leaseClient.acquire(any())).thenReturn(R.ok(acquireResp(assignment(TENANT_B))));

        manager.renewAndSelfCheck();

        assertThat(linkManager.isCollecting(TENANT_A)).isFalse();
        assertThat(manager.heldTenants()).containsExactly(TENANT_B);
        assertThat(linkManager.isCollecting(TENANT_B)).isTrue();
        assertThat(meterRegistry.get("iotcloud.lease.node_fenced").counter().count()).isEqualTo(1.0d);
        // 注册被调了两次：启动一次 + 重新注册一次
        verify(leaseClient, times(2)).register(any());
    }

    @Test
    @DisplayName("续约抛异常 → 不延长本地到期时间；到期后本地自检自行停采（不等 business）")
    void renewFailureShouldLeadToLocalSelfFence() {
        startWith(TENANT_A);
        when(leaseClient.renew(any())).thenThrow(new IllegalStateException("business 不可达"));

        manager.renewAndSelfCheck();

        // 续约失败时仍持有（还没过期），但失败被计数、错误被暴露
        assertThat(manager.heldTenants()).containsExactly(TENANT_A);
        assertThat(meterRegistry.get("iotcloud.lease.renew.failure").counter().count()).isEqualTo(1.0d);

        // 时间推进到本地到期时间之后：不调 business 也必须停采
        manager.renewAndSelfCheck(LocalDateTime.now().plusSeconds(120));

        assertThat(manager.heldTenants()).isEmpty();
        assertThat(linkManager.isCollecting(TENANT_A)).isFalse();
        assertThat(meterRegistry.get("iotcloud.lease.self_fenced").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("续约返回非成功信封（不抛）→ 同样计入失败，本地到期后自行停采")
    void renewRejectedEnvelopeShouldAlsoLeadToSelfFence() {
        startWith(TENANT_A);
        when(leaseClient.renew(any())).thenReturn(R.fail(500, "内部错误"));

        // 第一轮：还没过期 → 会真的发续约，拿到非成功信封 → 计失败、但不延长到期时间
        manager.renewAndSelfCheck();
        assertThat(meterRegistry.get("iotcloud.lease.renew.failure").counter().count()).isEqualTo(1.0d);
        assertThat(manager.heldTenants()).containsExactly(TENANT_A);

        // 第二轮（时间推到过期之后）：本地自检直接停采
        manager.renewAndSelfCheck(LocalDateTime.now().plusSeconds(120));

        assertThat(manager.heldTenants()).isEmpty();
        assertThat(linkManager.isCollecting(TENANT_A)).isFalse();
    }

    @Test
    @DisplayName("续约响应 data 为空 → 计失败（防御契约方返回空壳信封）")
    void renewWithEmptyDataShouldCountFailure() {
        startWith(TENANT_A);
        when(leaseClient.renew(any())).thenReturn(R.ok());

        manager.renewAndSelfCheck();

        assertThat(meterRegistry.get("iotcloud.lease.renew.failure").counter().count()).isEqualTo(1.0d);
        assertThat(manager.heldTenants()).containsExactly(TENANT_A);
    }

    @Test
    @DisplayName("本地已过期的租户：直接停采且不再向 business 发空续约（先自检、后续约）")
    void expiredHoldingShouldFenceWithoutCallingBusiness() {
        startWith(TENANT_A);

        manager.renewAndSelfCheck(LocalDateTime.now().plusSeconds(120));

        assertThat(manager.heldTenants()).isEmpty();
        assertThat(linkManager.collectingTenants()).isEmpty();
        verify(leaseClient, times(0)).renew(any());
    }

    @Test
    @DisplayName("握手完成前：调度器抢跑也不做任何重领（复核实测过 4/4 次启动抢跑，会污染指标与日志）")
    void refreshMustNotRunBeforeHandshake() {
        // 未调用 start()（= 未注册）：即使时间大幅推进，也只跳过，不发 acquire/renew
        manager.renewAndSelfCheck(LocalDateTime.now().plusSeconds(600));

        verify(leaseClient, times(0)).renew(any());
        verify(leaseClient, times(0)).acquire(any());
        verify(leaseClient, times(0)).register(any());
    }

    @Test
    @DisplayName("本地没有持有租户时：跳过续约（不制造空调用）；握手后到点才重领")
    void emptyHoldingsShouldSkipRenewUntilRefreshDue() {
        when(leaseClient.register(any())).thenReturn(R.ok());
        when(leaseClient.acquire(any())).thenReturn(R.ok(acquireResp()));
        manager.start();
        properties.setAcquireIntervalMs(60_000L);

        // 握手刚完成：未到重领间隔
        manager.renewAndSelfCheck(LocalDateTime.now().plusSeconds(10));
        verify(leaseClient, times(0)).renew(any());
        verify(leaseClient, times(1)).acquire(any());

        // 到点：重领（仍然没有租户，但确实调了一次）
        manager.renewAndSelfCheck(LocalDateTime.now().plusSeconds(70));
        verify(leaseClient, times(2)).acquire(any());
    }

    @Test
    @DisplayName("周期重领：到点后把别的节点遗留的租户接过来（否则「待接管」永远无人接手）")
    void refreshShouldTakeOverOrphanTenants() {
        properties.setAcquireIntervalMs(60_000L);
        // 第一次领取只拿到 A（长 TTL：避免它在 +70s 时已过期而被自检停采，干扰「重领」的断言）
        when(leaseClient.register(any())).thenReturn(R.ok());
        when(leaseClient.acquire(any())).thenReturn(R.ok(acquireResp(assignment(TENANT_A, 600))));
        manager.start();
        // business 把孤儿租户 B 分给本节点（重领时才可见）
        when(leaseClient.acquire(any()))
            .thenReturn(R.ok(acquireResp(assignment(TENANT_A, 600), assignment(TENANT_B, 600))));

        // 未到重领间隔（60s）：不重复领取
        manager.renewAndSelfCheck(LocalDateTime.now().plusSeconds(10));
        verify(leaseClient, times(1)).acquire(any());
        assertThat(manager.heldTenants()).containsExactly(TENANT_A);

        // 到点：重领并接管 B（A 已持有，不算新增）
        manager.renewAndSelfCheck(LocalDateTime.now().plusSeconds(70));

        verify(leaseClient, times(2)).acquire(any());
        assertThat(manager.heldTenants()).containsExactlyInAnyOrder(TENANT_A, TENANT_B);
        assertThat(linkManager.isCollecting(TENANT_B)).isTrue();
        assertThat(meterRegistry.get("iotcloud.lease.acquired").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("周期重领拿到非成功信封：只记错误，不影响已在采的租户")
    void refreshRejectedEnvelopeShouldNotAffectHoldings() {
        properties.setAcquireIntervalMs(60_000L);
        when(leaseClient.register(any())).thenReturn(R.ok());
        when(leaseClient.acquire(any())).thenReturn(R.ok(acquireResp(assignment(TENANT_A, 600))));
        manager.start();
        when(leaseClient.acquire(any())).thenReturn(R.fail(500, "内部错误"));

        manager.renewAndSelfCheck(LocalDateTime.now().plusSeconds(70));

        assertThat(manager.heldTenants()).containsExactly(TENANT_A);
        assertThat(meterRegistry.get("iotcloud.lease.acquired").counter().count()).isZero();
    }

    @Test
    @DisplayName("周期重领失败：只记错误，不影响已在采的租户（下一轮再试）")
    void refreshFailureShouldNotAffectExistingHoldings() {
        properties.setAcquireIntervalMs(60_000L);
        when(leaseClient.register(any())).thenReturn(R.ok());
        when(leaseClient.acquire(any())).thenReturn(R.ok(acquireResp(assignment(TENANT_A, 600))));
        manager.start();
        when(leaseClient.acquire(any())).thenThrow(new IllegalStateException("business 抖了一下"));

        manager.renewAndSelfCheck(LocalDateTime.now().plusSeconds(70));

        assertThat(manager.heldTenants()).containsExactly(TENANT_A);
        assertThat(linkManager.isCollecting(TENANT_A)).isTrue();
    }

    @Test
    @DisplayName("启动握手后 nodeFenced 重新注册失败 → 保持停采且不抛出（下一轮再试）")
    void reRegisterFailureShouldKeepNodeFenced() {
        startWith(TENANT_A);
        LeaseRenewResp fenced = new LeaseRenewResp();
        fenced.setNodeFenced(true);
        when(leaseClient.renew(any())).thenReturn(R.ok(fenced));
        when(leaseClient.register(any())).thenReturn(R.fail(500, "business 挂了"));

        manager.renewAndSelfCheck();

        assertThat(linkManager.collectingTenants()).isEmpty();
        assertThat(manager.heldTenants()).isEmpty();
        assertThat(meterRegistry.get("iotcloud.lease.node_fenced").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("nodeFenced 后重新注册成功但领取失败 → 保持停采、不抛（复核 D11 指出的覆盖缺口）")
    void reRegisterOkButAcquireFailureShouldKeepNodeFenced() {
        startWith(TENANT_A);
        LeaseRenewResp fenced = new LeaseRenewResp();
        fenced.setNodeFenced(true);
        when(leaseClient.renew(any())).thenReturn(R.ok(fenced));
        when(leaseClient.register(any())).thenReturn(R.ok());
        // 重新注册成功，但领取失败
        when(leaseClient.acquire(any())).thenReturn(R.fail(500, "内部错误"));

        manager.renewAndSelfCheck();

        assertThat(linkManager.collectingTenants()).isEmpty();
        assertThat(manager.heldTenants()).isEmpty();
        assertThat(meterRegistry.get("iotcloud.lease.node_fenced").counter().count()).isEqualTo(1.0d);
    }

    /** 常见前置：注册 + 领取给定租户，并让注册/领取返回成功。 */
    private void startWith(Long... tenantIds) {
        when(leaseClient.register(any())).thenReturn(R.ok());
        LeaseAssignmentDto[] assignments = new LeaseAssignmentDto[tenantIds.length];
        for (int i = 0; i < tenantIds.length; i++) {
            assignments[i] = assignment(tenantIds[i]);
        }
        when(leaseClient.acquire(any())).thenReturn(R.ok(acquireResp(assignments)));
        manager.start();
    }

    private LeaseAcquireResp acquireResp(LeaseAssignmentDto... assignments) {
        LeaseAcquireResp resp = new LeaseAcquireResp();
        resp.setAccessNode(NODE);
        resp.setAssignments(List.of(assignments));
        return resp;
    }

    private LeaseAssignmentDto assignment(Long tenantId) {
        return assignment(tenantId, 30);
    }

    /** 指定剩余有效期（重领类用例需要长 TTL，否则「领回来又过期」会污染断言）。 */
    private LeaseAssignmentDto assignment(Long tenantId, long ttlSeconds) {
        LeaseAssignmentDto dto = new LeaseAssignmentDto();
        dto.setTenantId(tenantId);
        dto.setAccessNode(NODE);
        dto.setLeaseExpireAt(LocalDateTime.now().plusSeconds(ttlSeconds));
        dto.setEpoch(1L);
        dto.setState(LeaseState.ACTIVE);
        return dto;
    }

    private LeaseRenewResp renewResp(List<LeaseRenewAck> acks) {
        return renewResp(acks, List.of());
    }

    private LeaseRenewResp renewResp(List<LeaseRenewAck> acks, List<Long> revoked) {
        LeaseRenewResp resp = new LeaseRenewResp();
        resp.setRenewedLeases(acks);
        resp.setRevokedTenantIds(revoked);
        return resp;
    }

    private LeaseRenewAck ack(Long tenantId, LocalDateTime expireAt, long epoch) {
        LeaseRenewAck ack = new LeaseRenewAck();
        ack.setTenantId(tenantId);
        ack.setLeaseExpireAt(expireAt);
        ack.setEpoch(epoch);
        return ack;
    }

    @Test
    @DisplayName("本地快照的过期判据就是契约里的 LeaseEpochRules（不另写一套）")
    void snapshotShouldUseContractRules() {
        LocalDateTime now = LocalDateTime.now();

        assertThat(new LeaseSnapshot(now.plusSeconds(1), 1L).mustSelfFence(now)).isFalse();
        assertThat(new LeaseSnapshot(now.minusSeconds(1), 1L).mustSelfFence(now)).isTrue();
    }
}
