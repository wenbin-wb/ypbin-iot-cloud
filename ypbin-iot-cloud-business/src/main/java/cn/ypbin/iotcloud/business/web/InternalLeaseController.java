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
package cn.ypbin.iotcloud.business.web;

import cn.ypbin.iotcloud.api.lease.AccessNodeRegisterReq;
import cn.ypbin.iotcloud.api.lease.AssignmentQueryReq;
import cn.ypbin.iotcloud.api.lease.LeaseAcquireReq;
import cn.ypbin.iotcloud.api.lease.LeaseAcquireResp;
import cn.ypbin.iotcloud.api.lease.LeaseAssignmentDto;
import cn.ypbin.iotcloud.api.lease.LeaseReleaseReq;
import cn.ypbin.iotcloud.api.lease.LeaseRenewReq;
import cn.ypbin.iotcloud.api.lease.LeaseRenewResp;
import cn.ypbin.iotcloud.api.lease.TenantEpochBatchResp;
import cn.ypbin.iotcloud.core.lease.LeaseService;
import cn.ypbin.starter.core.model.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租约维护的内部端点（IOT-CLOUD-SPEC.md §3.1①⑦ 的 access↔business 契约）。
 *
 * <p>路径与 {@code ILeaseClient} 的 Feign 声明<b>逐字对应</b>（{@code /internal/lease}），
 * 改这里必须同步改契约接口，否则两侧会在运行时 404。这条约束由
 * {@code ypbin-iot-cloud-architecture-tests} 的 {@code LeaseContractConsistencyTest}
 * 用反射逐端点比对来强制（含「至少扫到 6 个端点」的自检，防止空跑）。</p>
 *
 * <p>三个刻意的取舍：</p>
 * <ol>
 *   <li><b>不带 {@code @Idempotent} / {@code @Log}</b>：它们面向用户发起的写操作（防重、留业务日志）。
 *       本组端点由 access 节点周期性调用（10s 一次续约），套上会变成噪音，而幂等性本来就是契约语义
 *       （{@code register} 幂等、{@code acquire} 只续期不重复分配）；</li>
 *   <li><b>入站信任靠守卫，不靠网络位置</b>：{@code /internal/**} 由 {@code InternalTokenGuardInterceptor}
 *       fail-closed 校验 {@code X-Internal-Token}（common 模块装配，随本模块的依赖自动生效）；</li>
 *   <li><b>返回统一信封</b>：全部走 {@link R}，异常由 starter 的全局异常处理器转成 HTTP 200 + {@code R.code}
 *       （因此 business 必须依赖 {@code ypbin-starter-web}）。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@RestController
@RequestMapping(InternalLeaseController.BASE_PATH)
@RequiredArgsConstructor
public class InternalLeaseController {

    /** 内部端点前缀；必须与 {@code ILeaseClient} 的 {@code path} 一致。 */
    public static final String BASE_PATH = "/internal/lease";

    private final LeaseService leaseService;

    /**
     * 注册节点（幂等）。
     *
     * @param req 注册请求（节点标识 + 容量）
     * @return 统一响应
     */
    @PostMapping("/register")
    public R<Void> register(@Valid @RequestBody AccessNodeRegisterReq req) {
        leaseService.register(req.getAccessNode(), req.getMaxTenants());
        return R.ok();
    }

    /**
     * 领取分给该节点的租户。
     *
     * @param req 领取请求
     * @return 该节点当前的租户归属
     */
    @PostMapping("/acquire")
    public R<LeaseAcquireResp> acquire(@Valid @RequestBody LeaseAcquireReq req) {
        return R.ok(leaseService.acquire(req.getAccessNode()));
    }

    /**
     * 续约（周期性），响应携带 self-fencing 判据。
     *
     * @param req 续约请求
     * @return 续约结果（逐租户回执 + 被撤销租户 + 节点级 fencing 信号）
     */
    @PostMapping("/renew")
    public R<LeaseRenewResp> renew(@Valid @RequestBody LeaseRenewReq req) {
        return R.ok(leaseService.renew(req.getAccessNode(), req.getLeases()));
    }

    /**
     * 释放租约（节点正常下线）。
     *
     * @param req 释放请求
     * @return 统一响应
     */
    @PostMapping("/release")
    public R<Void> release(@Valid @RequestBody LeaseReleaseReq req) {
        leaseService.release(req.getAccessNode(), req.getTenantIds());
        return R.ok();
    }

    /**
     * 查询某个租户的当前归属。
     *
     * @param req 查询请求
     * @return 归属信息；从未分配过时 {@code data} 为 {@code null}（语义是「当前无归属」）
     */
    @PostMapping("/assignment")
    public R<LeaseAssignmentDto> queryAssignment(@Valid @RequestBody AssignmentQueryReq req) {
        return R.ok(leaseService.queryAssignment(req.getTenantId()).orElse(null));
    }

    /**
     * 批量查询全部租户的台账版本号（周期对账用）。
     *
     * @return 全部租户的 epoch + 读取时刻
     */
    @GetMapping("/epochs")
    public R<TenantEpochBatchResp> batchEpoch() {
        return R.ok(leaseService.batchEpoch());
    }
}
