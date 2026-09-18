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

import lombok.Getter;

/**
 * 租约状态。
 *
 * <p>状态流转：{@link #ACTIVE} —（到期但未被接管）→ {@link #PENDING_TAKEOVER} —（新节点接管）→
 * {@link #ACTIVE}（换节点）；节点主动下线为 {@link #RELEASED}。</p>
 *
 * <p>「到期未接管」必须是一个<b>可被 business 判定出来</b>的状态：否则租户会静默离线
 * （IOT-CLOUD-SPEC.md §3.1① 明确指出这是 v3 的缺口）。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
@Getter
public enum LeaseState {

    /** 租约有效，节点正在采集。 */
    ACTIVE("active", "租约有效"),

    /** 租约已过期、尚未被新节点接管（business 扫描发现后置为该状态）。 */
    PENDING_TAKEOVER("pending_takeover", "待接管"),

    /** 节点主动释放（正常下线）。 */
    RELEASED("released", "已释放");

    private final String code;

    private final String desc;

    LeaseState(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
