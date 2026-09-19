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
package cn.ypbin.iotcloud.gateway.auth;

import cn.ypbin.starter.gateway.auth.GatewayAuthProvider;
import cn.ypbin.starter.gateway.auth.GatewayAuthResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 平台网关的认证器（M0a：<b>fail-closed 占位实现</b>）。
 *
 * <p><b>为什么必须有这个类</b>：starter 在 {@code ypbin.gateway.auth.enabled=true} 但容器里没有
 * {@link GatewayAuthProvider} 时，只打一条 WARN 并且<b>不注册鉴权过滤器</b>——也就是
 * 「配了鉴权却全部放行」（fail-open）。提供一个显式的 Provider 才能把这条洞堵上，
 * 并由 {@code GatewayAuthProviderPresenceTest} 长期守住。</p>
 *
 * <p><b>M0a 行为</b>：认证服务（租户/用户/权限）要到 M0b 才从 admin 移植，因此这里对所有非白名单请求
 * 一律判<b>未认证</b>——白名单（健康探针/文档）仍由 {@code ypbin.gateway.auth.exclude-paths} 放行。
 * 这样 M0a 的验收「网关鉴权生效（无令牌 → HTTP 200 + {@code R.code=401}）」是真的成立，
 * 而不是靠「没有过滤器」造成的假象。</p>
 *
 * <p><b>M0b 替换计划</b>：改为校验访问令牌（Sa-Token 或等价方案，与 auth 服务一致），
 * 认证成功后用 {@link GatewayAuthResult#success(java.util.Map)} 签发
 * {@code X-User-Id}/{@code X-Tenant-Id} 等身份头，并同时签发来源标记。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
@Component
public class PlatformGatewayAuthProvider implements GatewayAuthProvider {

    private static final Logger log = LoggerFactory.getLogger(PlatformGatewayAuthProvider.class);

    @Override
    public Mono<GatewayAuthResult> authenticate(ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) {
            return Mono.just(GatewayAuthResult.failure("未携带访问令牌"));
        }
        log.warn("网关收到访问令牌，但 M0a 骨架尚未接入认证服务，按未认证拒绝；M0b 将落地令牌校验：path={}",
            exchange.getRequest().getURI().getPath());
        return Mono.just(GatewayAuthResult.failure("认证服务尚未接入（M0a 骨架），请等待 M0b"));
    }
}
