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
import java.util.ArrayList;
import java.util.List;
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

    /** 内部端点所在的路径段名。 */
    static final String INTERNAL_SEGMENT = "internal";

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
        if (!isInternalApiPath(path)) {
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

    /**
     * 是否为内部端点路径。
     *
     * <p>结构化判据（不是子串匹配）：网关的约定是「URL 第一段 = 服务短名，转发前 StripPrefix 剥掉」，
     * 因此内部端点只有两种形态——{@code /internal/...}（不带服务前缀）与
     * {@code /{服务}/internal/...}（带前缀，剥掉后即形态一）。</p>
     *
     * <p><b>必须按路由器的归一化规则先归一化再判</b>，否则会漏判。独立复核实测过两个绕过形态，
     * 并证明「只要网关不再剥离 {@code X-Internal-Token}，它们就能同时绕过本过滤器与下游守卫、
     * 从外网拿到内部数据」：</p>
     * <ul>
     *   <li>{@code /business//internal/lease/epochs}——重复斜杠让朴素的 {@code split("/")} 得到空段，
     *       而 StripPrefix 的 tokenizer <b>丢弃空段</b>，转发后就是 {@code /internal/lease/epochs}；</li>
     *   <li>{@code /business/internal;a=b/lease/epochs}——矩阵参数让该段不等于 {@code internal}，
     *       而 Spring MVC 匹配时会剥掉 {@code ;a=b}，同样命中 {@code /internal/**}。</li>
     * </ul>
     *
     * <p>另外还有第三条同类（本类作者自查发现）：{@code /business/devices/../internal/x}——
     * 朴素判据看到的是中段 {@code devices} 与 {@code ..}，而下游容器会把 {@code ..} 解析掉。</p>
     *
     * <p>因此这里依次做：<b>去掉矩阵参数 → 丢弃空段与 {@code .} → 解析 {@code ..}（弹出上一段）</b>，
     * 再只看前两段是否等于 {@code internal}（大小写不敏感：路由器大小写敏感，这里宁可多拦）。</p>
     *
     * @param path 请求路径
     * @return 是否应拦
     */
    boolean isInternalApiPath(String path) {
        List<String> segments = normalize(path);
        return !segments.isEmpty() && (isInternal(segments.get(0))
            || (segments.size() > 1 && isInternal(segments.get(1))));
    }

    /** 按路由器的归一化规则切段：去矩阵参数、丢空段与 {@code .}、解析 {@code ..}。 */
    private List<String> normalize(String path) {
        List<String> segments = new ArrayList<>();
        for (String raw : path.split("/")) {
            String segment = raw;
            int matrixParamIndex = segment.indexOf(';');
            if (matrixParamIndex >= 0) {
                segment = segment.substring(0, matrixParamIndex);
            }
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!segments.isEmpty()) {
                    segments.remove(segments.size() - 1);
                }
                continue;
            }
            segments.add(segment);
        }
        return segments;
    }

    /** 是否为内部端点段（大小写不敏感：路由器大小写敏感，这里宁可多拦）。 */
    private boolean isInternal(String segment) {
        return INTERNAL_SEGMENT.equalsIgnoreCase(segment);
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
