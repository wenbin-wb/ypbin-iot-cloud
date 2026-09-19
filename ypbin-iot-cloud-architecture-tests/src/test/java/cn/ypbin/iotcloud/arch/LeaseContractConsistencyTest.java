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
package cn.ypbin.iotcloud.arch;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iotcloud.api.lease.ILeaseClient;
import cn.ypbin.iotcloud.business.web.InternalLeaseController;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * 契约一致性门禁：{@code ILeaseClient}（access 侧的 Feign 声明）与
 * {@link InternalLeaseController}（business 侧的服务端实现）必须逐字对应。
 *
 * <p>为什么需要它：这两份东西<b>分居两个模块、由不同的人改</b>，而它们之间唯一的联系是
 * 路径字符串。改了一侧而忘了另一侧，编译不会报错、单测也不会红——只会在运行期 404
 * （而且往往要等 access 真正接上才暴露）。此前 {@code InternalLeaseController} 的类注释
 * 声称「有契约一致性用例会拦」，但当时**并不存在**这样的用例，属于不可执行的声明
 * （教训二十五）。本类就是那条用例。</p>
 *
 * <p>做法：用反射读注解（不解析源码文本——按文本匹配会被注释、
 * 换行与常量拼接骗过，教训二十三），并带「至少扫到 6 个端点」的自检防止空跑。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
class LeaseContractConsistencyTest {

    /** 契约里应有的端点数（register/acquire/renew/release/assignment/epochs）。 */
    private static final int EXPECTED_ENDPOINT_COUNT = 6;

    @Test
    @DisplayName("路径前缀一致：Feign 的 path 与控制器类的 @RequestMapping 必须相同")
    void basePathMustMatch() {
        FeignClient feignClient = ILeaseClient.class.getAnnotation(FeignClient.class);
        RequestMapping controllerMapping = InternalLeaseController.class.getAnnotation(RequestMapping.class);

        assertThat(feignClient).as("契约接口必须声明 @FeignClient").isNotNull();
        assertThat(controllerMapping).as("控制器必须声明 @RequestMapping").isNotNull();
        assertThat(controllerMapping.value())
            .as("控制器路径前缀必须与 Feign 的 path 逐字一致，否则全部端点都会 404")
            .containsExactly(feignClient.path());
    }

    @Test
    @DisplayName("逐个端点一致：HTTP 方法 + 路径（契约侧与服务端侧必须完全相同）")
    void endpointsMustMatch() {
        Set<String> contractEndpoints = endpointsOf(ILeaseClient.class);
        Set<String> controllerEndpoints = endpointsOf(InternalLeaseController.class);

        // 自检：任一侧扫不到预期数量都说明本用例在空跑或漏扫（教训二十三）
        assertThat(contractEndpoints)
            .as("契约接口至少应扫到 %s 个端点（扫不到=本用例无意义）", EXPECTED_ENDPOINT_COUNT)
            .hasSizeGreaterThanOrEqualTo(EXPECTED_ENDPOINT_COUNT);
        assertThat(controllerEndpoints)
            .as("控制器至少应扫到 %s 个端点", EXPECTED_ENDPOINT_COUNT)
            .hasSizeGreaterThanOrEqualTo(EXPECTED_ENDPOINT_COUNT);

        assertThat(controllerEndpoints)
            .as("服务端端点集合必须与契约完全一致（多一个=契约没写；少一个=调用方会 404）")
            .containsExactlyInAnyOrderElementsOf(contractEndpoints);
    }

    /** 反射提取一个类型上所有「HTTP 方法 + 路径」组合。 */
    private Set<String> endpointsOf(Class<?> type) {
        Set<String> endpoints = new LinkedHashSet<>();
        for (Method method : type.getDeclaredMethods()) {
            for (Annotation annotation : method.getAnnotations()) {
                String endpoint = endpointOf(annotation);
                if (endpoint != null) {
                    endpoints.add(endpoint);
                    break;
                }
            }
        }
        return endpoints;
    }

    /** 把 Spring 的映射注解归一成 {@code "GET /epochs"} 这样的可比字符串；非映射注解返回 {@code null}。 */
    private String endpointOf(Annotation annotation) {
        if (annotation instanceof PostMapping postMapping) {
            return "POST " + firstPath(postMapping.value(), postMapping.path());
        }
        if (annotation instanceof GetMapping getMapping) {
            return "GET " + firstPath(getMapping.value(), getMapping.path());
        }
        if (annotation instanceof PutMapping putMapping) {
            return "PUT " + firstPath(putMapping.value(), putMapping.path());
        }
        if (annotation instanceof DeleteMapping deleteMapping) {
            return "DELETE " + firstPath(deleteMapping.value(), deleteMapping.path());
        }
        if (annotation instanceof PatchMapping patchMapping) {
            return "PATCH " + firstPath(patchMapping.value(), patchMapping.path());
        }
        if (annotation instanceof RequestMapping requestMapping) {
            String httpMethod = requestMapping.method().length == 0
                ? RequestMethod.GET.name()
                : requestMapping.method()[0].name();
            return httpMethod + " " + firstPath(requestMapping.value(), requestMapping.path());
        }
        return null;
    }

    /** 取注解里的第一个路径（{@code value} 与 {@code path} 互为别名，只填一个）。 */
    private String firstPath(String[] value, String[] path) {
        String[] candidate = value.length > 0 ? value : path;
        return candidate.length > 0 ? candidate[0] : "";
    }
}
