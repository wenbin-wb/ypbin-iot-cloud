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

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 架构规则集合的唯一来源。
 *
 * <p>规则<b>只在这里定义一次</b>，由两个测试类分别消费：</p>
 * <ul>
 *   <li>{@code ArchitectureRulesTest}：对<b>主源码</b>执行，要求全部通过；</li>
 *   <li>{@code RuleEffectivenessTest}：对<b>故意违规的夹具</b>执行，要求<b>必须报错</b>。</li>
 * </ul>
 *
 * <p>这样拆分的原因是母仓总结的教训：ArchUnit 规则很容易写成「恒为真」而永不报错，
 * 把「断言通过」与「断言失败」放在同一个测试类里会互相干扰。</p>
 *
 * <p><b>与 ypbin-iot-starter 的差异（有意为之）</b>：本仓没有「core 层零 Spring」这类规则 ——
 * 本仓的 {@code common} / {@code api} 必然含 Spring；规则集按 IOT-CLOUD-SPEC.md §9.1 的说明
 * 采用母仓 ypbin-starter 的口径（分层 / 编码规范），不照搬 iot-starter 的零 Spring 边界。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
public final class ArchitectureRules {

    /** 本仓全部包。 */
    public static final String ROOT = "cn.ypbin.iotcloud..";

    /** 基础库包。 */
    public static final String COMMON = "cn.ypbin.iotcloud.common..";

    /** 服务间契约包。 */
    public static final String API = "cn.ypbin.iotcloud.api..";

    /** 实现库包：认证。 */
    public static final String AUTH = "cn.ypbin.iotcloud.auth..";

    /** 实现库包：业务核心。 */
    public static final String CORE = "cn.ypbin.iotcloud.core..";

    /** 实现库包：开放 API。 */
    public static final String OPENAPI = "cn.ypbin.iotcloud.openapi..";

    /** 部署单元包：网关。 */
    public static final String GATEWAY = "cn.ypbin.iotcloud.gateway..";

    /** 部署单元包：业务服务。 */
    public static final String BUSINESS = "cn.ypbin.iotcloud.business..";

    /** 部署单元包：设备接入。 */
    public static final String ACCESS = "cn.ypbin.iotcloud.access..";

    /**
     * 禁止字面量集合使用的工厂方法。
     *
     * <p>母仓铁律：无数据一律返回空集合，且字面量集合统一用 {@code List.of} / {@code Map.of} / {@code Set.of}。</p>
     */
    private static final Set<String> FORBIDDEN_COLLECTION_FACTORIES = Set.of(
            "emptyList", "emptyMap", "emptySet", "singletonList", "singletonMap", "singleton");

    /**
     * 谓词：调用 {@code printStackTrace()}。
     *
     * <p><b>不能</b>写成 {@code callMethod(Throwable.class, "printStackTrace")}：调用点的 owner
     * 通常是子类（如 {@code RuntimeException}），按精确 owner 匹配会静默漏掉全部违规，
     * 规则恒为真（母仓已踩过这个坑）。这里按「目标 owner 可赋值给 Throwable」判定。</p>
     */
    private static final DescribedPredicate<JavaMethodCall> CALLS_PRINT_STACK_TRACE =
            DescribedPredicate.describe("调用 printStackTrace()", call ->
                    call.getName().equals("printStackTrace")
                            && call.getTargetOwner().isAssignableTo(Throwable.class));

    /**
     * 谓词：访问 {@code System.out} 或 {@code System.err}。
     */
    private static final DescribedPredicate<JavaFieldAccess> ACCESSES_SYSTEM_STREAM =
            DescribedPredicate.describe("访问 System.out 或 System.err", access ->
                    access.getTargetOwner().isEquivalentTo(System.class)
                            && ("out".equals(access.getName()) || "err".equals(access.getName())));

    /**
     * 谓词：调用 {@code Collections.emptyXxx} / {@code Collections.singletonXxx}。
     */
    private static final DescribedPredicate<JavaMethodCall> CALLS_FORBIDDEN_COLLECTION_FACTORY =
            DescribedPredicate.describe("调用 Collections.emptyXxx 或 Collections.singletonXxx", call ->
                    call.getTargetOwner().isEquivalentTo(Collections.class)
                            && FORBIDDEN_COLLECTION_FACTORIES.contains(call.getName()));

    private ArchitectureRules() {
    }

    /**
     * 五个库模块的包（spec §2）。
     *
     * @return 包名列表
     */
    public static List<String> libraryPackages() {
        return List.of(COMMON, API, AUTH, CORE, OPENAPI);
    }

    /**
     * 三个部署单元的包（spec §4.2）。
     *
     * @return 包名列表
     */
    public static List<String> applicationPackages() {
        return List.of(GATEWAY, BUSINESS, ACCESS);
    }

    /**
     * 基础库不得依赖契约层、实现库与部署单元。
     *
     * <p>{@code common} 是最底层：它一旦依赖上层，「按需引入」就退化为「引入一个必须拖入全部」。</p>
     *
     * @return 规则
     */
    public static List<ArchRule> commonMustNotDependOnUpperLayers() {
        return List.of(
                noClasses()
                        .that().resideInAPackage(COMMON)
                        .should().dependOnClassesThat()
                        .resideInAnyPackage(API, AUTH, CORE, OPENAPI, GATEWAY, BUSINESS, ACCESS)
                        .because("common 是最底层基础库，依赖契约层/实现库/部署单元会让按需引入失效（spec §2 / §4.2）"));
    }

    /**
     * 服务间契约层不得依赖实现库与部署单元。
     *
     * <p>{@code api} 只放 Feign 接口与 DTO：契约一旦依赖实现，任何实现变更都会变成契约破坏。</p>
     *
     * @return 规则
     */
    public static List<ArchRule> apiMustNotDependOnImplementations() {
        return List.of(
                noClasses()
                        .that().resideInAPackage(API)
                        .should().dependOnClassesThat()
                        .resideInAnyPackage(AUTH, CORE, OPENAPI, GATEWAY, BUSINESS, ACCESS)
                        .because("api 是服务间契约层，不得依赖实现库或部署单元（spec §2）"));
    }

    /**
     * 实现库不得依赖部署单元。
     *
     * <p>三个实现库被部署单元②「组装」，反向依赖会形成循环。</p>
     *
     * @return 规则
     */
    public static List<ArchRule> implementationsMustNotDependOnApplications() {
        return List.of(
                noClasses()
                        .that().resideInAnyPackage(AUTH, CORE, OPENAPI)
                        .should().dependOnClassesThat()
                        .resideInAnyPackage(GATEWAY, BUSINESS, ACCESS)
                        .because("auth/core/openapi 被部署单元组装，反向依赖部署单元会形成循环（spec §4.2）"));
    }

    /**
     * 编码铁律（字节码级）：{@code printStackTrace}、{@code System.out/err}、
     * {@code Collections.emptyXxx/singletonXxx}。
     *
     * <p>这几条都能在字节码层可靠判定。反之，下面两类<b>刻意不放在字节码层</b>：</p>
     * <ul>
     *   <li>{@code @Data} 为 SOURCE 级保留，编译后注解信息不存在；</li>
     *   <li>{@code ordinal()}：{@code switch(enum)} 会被 javac 编译成 ordinal 查表，
     *       字节码规则必然误报。</li>
     * </ul>
     * <p>这两条改由 {@code SourceConventionTest} 做源码扫描。</p>
     *
     * @return 规则
     */
    public static List<ArchRule> codingRules() {
        return List.of(
                noClasses()
                        .that().resideInAPackage(ROOT)
                        .should().callMethodWhere(CALLS_PRINT_STACK_TRACE)
                        .because("禁止 printStackTrace：必须走日志框架并传完整堆栈"),
                noClasses()
                        .that().resideInAPackage(ROOT)
                        .should().accessFieldWhere(ACCESSES_SYSTEM_STREAM)
                        .because("禁止直接使用 System.out / System.err：绕过日志框架，生产环境不可追踪"),
                noClasses()
                        .that().resideInAPackage(ROOT)
                        .should().callMethodWhere(CALLS_FORBIDDEN_COLLECTION_FACTORY)
                        .because("字面量集合必须用 List.of / Map.of / Set.of，禁用 Collections.emptyXxx / singletonXxx"));
    }

    /**
     * 全部规则（顺序固定，供测试逐条消费）。
     *
     * @return 规则列表
     */
    public static List<ArchRule> allRules() {
        return Stream.of(
                        commonMustNotDependOnUpperLayers(),
                        apiMustNotDependOnImplementations(),
                        implementationsMustNotDependOnApplications(),
                        codingRules())
                .flatMap(List::stream)
                .toList();
    }
}
