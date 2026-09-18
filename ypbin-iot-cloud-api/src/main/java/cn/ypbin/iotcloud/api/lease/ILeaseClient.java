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
package cn.ypbin.iotcloud.api.lease;

import cn.ypbin.iotcloud.api.lease.config.LeaseFeignConfiguration;
import cn.ypbin.starter.core.model.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * access ↔ business 的租约与归属契约（IOT-CLOUD-SPEC.md §3.1⑦，<b>M0a 定死</b>）。
 *
 * <p>六个方法对应 spec 的六项：<b>注册 / 领取 / 续约 / 释放 / 查询归属 / 批量查 epoch</b>。
 * 策略（哈希/注册表/静态分配）可延后，但本契约不可延后——它是 access 与 business 之间唯一的
 * 归属真相入口，改它等于同时改两侧。</p>
 *
 * <p>调用侧约束：所有方法都是内部端点调用，必须携带内部凭证头并由
 * {@link LeaseFeignConfiguration} 提供<b>显式超时</b>（禁止无超时的默认客户端）。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
@FeignClient(name = ILeaseClient.SERVICE_NAME, contextId = "leaseClient",
        path = "/internal/lease", configuration = LeaseFeignConfiguration.class)
public interface ILeaseClient {

    /** 目标服务名（business 部署单元）。 */
    String SERVICE_NAME = "ypbin-iot-cloud-business";

    /**
     * 注册节点（幂等）。
     *
     * @param req 注册请求
     * @return 统一响应
     */
    @PostMapping("/register")
    R<Void> register(@RequestBody AccessNodeRegisterReq req);

    /**
     * 领取分给该节点的租户。
     *
     * @param req 领取请求
     * @return 租户归属列表
     */
    @PostMapping("/acquire")
    R<LeaseAcquireResp> acquire(@RequestBody LeaseAcquireReq req);

    /**
     * 续约（周期性，默认 10s）。
     *
     * <p><b>响应里的 {@code revokedTenantIds} 是 self-fencing 的判据</b>：节点必须对其中每个租户
     * 立即断链并停止采集，否则可能出现新旧节点同时轮询同一台设备。</p>
     *
     * @param req 续约请求
     * @return 续约结果（含被撤销的租户）
     */
    @PostMapping("/renew")
    R<LeaseRenewResp> renew(@RequestBody LeaseRenewReq req);

    /**
     * 释放租约（节点正常下线）。
     *
     * @param req 释放请求
     * @return 统一响应
     */
    @PostMapping("/release")
    R<Void> release(@RequestBody LeaseReleaseReq req);

    /**
     * 查询某个租户的当前归属。
     *
     * @param req 查询请求
     * @return 归属信息
     */
    @PostMapping("/assignment")
    R<LeaseAssignmentDto> queryAssignment(@RequestBody AssignmentQueryReq req);

    /**
     * 批量查询全部租户的台账版本号（周期对账用，一次拉全量）。
     *
     * @return 全部租户的 epoch
     */
    @GetMapping("/epochs")
    R<TenantEpochBatchResp> batchEpoch();
}
