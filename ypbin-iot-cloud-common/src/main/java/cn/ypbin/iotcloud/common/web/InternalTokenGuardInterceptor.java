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
package cn.ypbin.iotcloud.common.web;

import cn.ypbin.iotcloud.common.config.InternalProperties;
import cn.ypbin.iotcloud.common.constant.InternalTokenConstants;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.core.util.LogSanitizer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 内部端点（{@code /internal/**}）调用凭证守卫。
 *
 * <p>只放行持有内部凭证的服务间直连；校验失败抛业务异常，由全局异常处理器转为
 * <b>HTTP 200 + {@code R.code=401}</b>（本仓统一异常口径，不使用 REST 4xx），不影响其它路径。</p>
 *
 * <p>三个要点（移植自 {@code ypbin-admin} 已验证实现，<b>不自行重造</b>）：</p>
 * <ol>
 *   <li><b>fail-closed</b>：凭证未配置时<b>整体拒绝</b>，绝不静默放行；</li>
 *   <li><b>常量时间比较</b>：用 {@link MessageDigest#isEqual} 而非 {@code equals}，避免计时侧信道；</li>
 *   <li><b>失败可观测</b>：记日志并带 URI（经 {@link LogSanitizer} 去除换行，防日志注入）。</li>
 * </ol>
 *
 * <p>定位说明：真正的底线是「内部端口不对不可信网络暴露」，本守卫是<b>纵深防御</b>，不是唯一防线。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
public class InternalTokenGuardInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(InternalTokenGuardInterceptor.class);

    private final InternalProperties internalProperties;

    public InternalTokenGuardInterceptor(InternalProperties internalProperties) {
        this.internalProperties = internalProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
            Object handler) {
        String configured = internalProperties.getToken();
        if (configured == null || configured.isBlank()) {
            log.error("内部调用凭证未配置（{}），已拒绝 /internal/** 请求：uri={}",
                InternalTokenConstants.TOKEN_PROPERTY,
                LogSanitizer.sanitize(request.getRequestURI()));
            throw new BusinessException(GlobalErrorCode.UNAUTHORIZED,
                "内部调用凭证未配置，请先配置 " + InternalTokenConstants.TOKEN_PROPERTY);
        }
        String presented = request.getHeader(InternalTokenConstants.TOKEN_HEADER);
        if (presented == null || !MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8))) {
            log.warn("内部调用凭证校验失败，已拒绝：uri={}",
                LogSanitizer.sanitize(request.getRequestURI()));
            throw new BusinessException(GlobalErrorCode.UNAUTHORIZED, "内部调用凭证校验失败");
        }
        return true;
    }
}
