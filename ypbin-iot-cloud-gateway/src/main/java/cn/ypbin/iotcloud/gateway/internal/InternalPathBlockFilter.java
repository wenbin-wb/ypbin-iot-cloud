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
package cn.ypbin.iotcloud.gateway.internal;

import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.core.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 内部端点（{@code /internal/**}）的<b>网关侧封堵</b>。
 *
 * <p>为什么必须有它：网关的路由是 {@code Path=/business/**} + {@code StripPrefix=1}，
 * 因此 {@code /business/internal/lease/epochs} 会被<b>剥掉前缀后打到 business 的
 * {@code /internal/lease/**}</b>。也就是说内部端点本来是「经网关可达」的，唯一防线是
 * 下游那个共享令牌——而网关又不剥离 {@code X-Internal-Token}，客户端可以自带它
 * （与 §4.4-2「客户端可伪造的头必须剥掉」的精神相悖）。</p>
 *
 * <p>本过滤器在路由之前直接拒绝任何含 {@code /internal/} 的路径，并返回与「不存在的接口」
 * 一致的信封（HTTP 200 + {@code R.code=404}，<b>不暴露内部拓扑</b>）。这样内部端点只剩
 * 「集群内直连」一条访问路径（§4.4 的定位：入站校验是纵深防御，真正的底线是端口不对不可信网络暴露）。</p>
 *
 * <p>顺序：{@link Ordered#HIGHEST_PRECEDENCE} —— {@link WebFilter} 包在网关处理器之外，
 * 因而它先于路由、也先于 starter 的鉴权全局过滤器执行（鉴权过滤器不该为内部路径发令牌）。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@Component
public class InternalPathBlockFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(InternalPathBlockFilter.class);

    /** 内部端点的路径片段：{@code /internal/...} 与 {@code /business/internal/...} 都会命中。 */
    static final String INTERNAL_SEGMENT = "/internal/";

    private final ObjectMapper objectMapper;

    /**
     * 构造内部路径封堵过滤器。
     *
     * @param objectMapper JSON 序列化器（与网关其它响应共用同一套信封）
     */
    public InternalPathBlockFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!path.contains(INTERNAL_SEGMENT)) {
            return chain.filter(exchange);
        }
        log.warn("网关拒绝内部端点访问（内部端点只允许集群内直连）：path={} remote={}",
            LogSanitizer.sanitize(path), exchange.getRequest().getRemoteAddress());
        return writeNotFound(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /** 写出与「接口不存在」一致的统一信封（不区分「路由不存在」与「内部端点被拦」，避免暴露拓扑）。 */
    private Mono<Void> writeNotFound(ServerWebExchange exchange) {
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(
                R.fail(GlobalErrorCode.NOT_FOUND.getCode(), "接口不存在"));
        } catch (JacksonException e) {
            // 不静默降级：序列化失败是编码/配置问题，必须带着堆栈暴露出来
            log.error("网关内部端点封堵响应序列化失败", e);
            throw new IllegalStateException("网关内部端点封堵响应序列化失败", e);
        }
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
