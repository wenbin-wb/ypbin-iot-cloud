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
package cn.ypbin.iotcloud.common.constant;

/**
 * 服务间内部调用凭证的约定常量。
 *
 * <p>「内部端点」（{@code /internal/**}）不对外暴露，但也不能只靠网络隔离：网关与各服务的
 * 直连（Feign）必须带凭证头，否则任何能连到端口的进程都能读写内部接口。本类把
 * <b>配置键</b>与<b>请求头名</b>固化为常量，供「出站携带侧」与「入站守卫侧」共用，
 * 避免两侧各写一份字符串而悄悄不一致。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
public final class InternalTokenConstants {

    /** 内部调用凭证的配置键（各服务经环境变量注入同一值）。 */
    public static final String TOKEN_PROPERTY = "ypbin.internal.token";

    /** 内部调用凭证的请求头名（出站携带与入站守卫必须一致）。 */
    public static final String TOKEN_HEADER = "X-Internal-Token";

    private InternalTokenConstants() {
    }
}
