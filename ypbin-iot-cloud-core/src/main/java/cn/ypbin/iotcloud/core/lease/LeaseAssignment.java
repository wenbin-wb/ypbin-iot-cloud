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

import cn.ypbin.iotcloud.api.lease.LeaseAssignmentDto;
import cn.ypbin.iotcloud.api.lease.LeaseEpochRules;
import cn.ypbin.iotcloud.api.lease.LeaseState;
import java.time.LocalDateTime;

/**
 * 租约归属的<b>内部状态</b>（M0a 由 {@link InMemoryLeaseStore} 承载，M0b 换成 {@code tenant_node_assignment} 表）。
 *
 * <p>存储里只保存「曾经被分配过」的租户：从未分配过的租户没有记录。因此这里
 * {@code accessNode} 与 {@code leaseExpireAt} 恒非空——把它们设计成可空，会让每个使用点都得判空，
 * 而「无归属」本来就该由「记录不存在」表达。</p>
 *
 * @param tenantId      租户 ID
 * @param accessNode    当前持有节点
 * @param leaseExpireAt 当前租约到期时间
 * @param epoch         台账版本号（归属被<b>接管</b>时递增；正常释放不递增）
 * @param state         租约状态
 * @author wenbin
 * @since 2026-09-19
 */
public record LeaseAssignment(Long tenantId, String accessNode, LocalDateTime leaseExpireAt, long epoch,
        LeaseState state) {

    /**
     * 该租约此刻是否由指定节点<b>有效持有</b>。
     *
     * <p>「有效」= 状态是 {@code ACTIVE} 且未过期。只判状态会漏判「进程还活着但租约已过期」
     * （§3.1① 特别点名的漏判），所以这里用组合判据。</p>
     */
    boolean heldBy(String accessNode, LocalDateTime now) {
        return state == LeaseState.ACTIVE && this.accessNode.equals(accessNode)
            && !LeaseEpochRules.isLeaseExpired(leaseExpireAt, now);
    }

    /**
     * 是否可被新节点接管。
     *
     * <p>两种情形：① 状态已是「待接管」（失效扫描已判定过）；② 状态仍是 {@code ACTIVE} 但**已过期**
     * ——后者说明旧节点可能已经死了而扫描还没跑到，接管同样是安全的（旧节点即使活着，
     * 它自己的租约也已过期，续约时会被告知撤销并 self-fencing）。</p>
     */
    boolean takeoverable(LocalDateTime now) {
        return state == LeaseState.PENDING_TAKEOVER
            || (state == LeaseState.ACTIVE && LeaseEpochRules.isLeaseExpired(leaseExpireAt, now));
    }

    /** 是否只是被正常释放：可重新分配，且**不递增** epoch（台账没变，只是没人采了）。 */
    boolean released() {
        return state == LeaseState.RELEASED;
    }

    /** 续期（或换节点接管）：落到新的到期时间与版本号，状态回到 {@code ACTIVE}。 */
    LeaseAssignment withLease(String node, LocalDateTime expireAt, long newEpoch) {
        return new LeaseAssignment(tenantId, node, expireAt, newEpoch, LeaseState.ACTIVE);
    }

    /** 置为「待接管」——失效扫描唯一的写操作。 */
    LeaseAssignment asPendingTakeover() {
        return new LeaseAssignment(tenantId, accessNode, leaseExpireAt, epoch, LeaseState.PENDING_TAKEOVER);
    }

    /** 置为「已释放」——节点正常下线。 */
    LeaseAssignment asReleased() {
        return new LeaseAssignment(tenantId, accessNode, leaseExpireAt, epoch, LeaseState.RELEASED);
    }

    /** 转成对外的契约 DTO。 */
    LeaseAssignmentDto toDto() {
        LeaseAssignmentDto dto = new LeaseAssignmentDto();
        dto.setTenantId(tenantId);
        dto.setAccessNode(accessNode);
        dto.setLeaseExpireAt(leaseExpireAt);
        dto.setEpoch(epoch);
        dto.setState(state);
        return dto;
    }
}
