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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.iotcloud.api.fixture.ApiDependsOnCoreFixture;
import cn.ypbin.iotcloud.api.fixture.ApiMarker;
import cn.ypbin.iotcloud.arch.fixture.CodingViolationFixture;
import cn.ypbin.iotcloud.common.fixture.CommonCompliantFixture;
import cn.ypbin.iotcloud.common.fixture.CommonDependsOnApiFixture;
import cn.ypbin.iotcloud.core.fixture.CoreDependsOnBusinessFixture;
import cn.ypbin.iotcloud.core.fixture.CoreMarker;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 架构规则<b>有效性自检</b>：对故意违规的测试夹具执行规则，断言规则确实会报错。
 *
 * <p><b>为什么必须有这个测试类</b>：ArchUnit 规则写错时不会报错，而是<b>静默放行</b> ——
 * 例如包名写错、匹配条件永不命中，规则就成了一句装饰。母仓已因此踩过多个坑
 * （{@code callMethod} 的 owner 是子类、{@code switch(enum)} 被编译成 ordinal 查表、
 * Lombok {@code @Data} 在字节码里不可见）。</p>
 *
 * <p><b>本仓比参考实现多一层防护</b>：ArchUnit 1.5.0 默认 {@code archRule.failOnEmptyShould=true}
 * （已用字节码核实：{@code AllowEmptyShould.AS_CONFIGURED.isAllowed()} 读的配置默认值是 TRUE），
 * 也就是「规则一个类都没评估到」本身就会抛 {@code AssertionError}。只断言「抛了 AssertionError」
 * 的自检会因此<b>假绿</b> —— 规则根本没生效，却因为空集报错而看起来通过了。
 * 所以这里的断言是「报错内容里必须出现被违规的夹具名」，而不是「抛了异常」。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
class RuleEffectivenessTest {

    /** ArchUnit 空选择集报错里的特征文本。 */
    private static final String EMPTY_SHOULD_MARKER = "failed to check any classes";

    private static JavaClasses allClasses;

    @BeforeAll
    static void importAllClasses() {
        // 故意**不带** DO_NOT_INCLUDE_TESTS：故意违规的夹具就在测试源码里。
        allClasses = new ClassFileImporter().importPackages("cn.ypbin.iotcloud");
    }

    @Test
    @DisplayName("SELF-00 夹具必须真的被导入（否则本类所有断言都是空跑）")
    void fixturesMustBeLoaded() {
        assertThat(allClasses.stream().map(JavaClass::getName))
                .as("故意违规的夹具必须真的被导入，否则下面的断言全是空的")
                .contains(
                        CommonCompliantFixture.class.getName(),
                        CommonDependsOnApiFixture.class.getName(),
                        ApiMarker.class.getName(),
                        ApiDependsOnCoreFixture.class.getName(),
                        CoreMarker.class.getName(),
                        CoreDependsOnBusinessFixture.class.getName(),
                        CodingViolationFixture.class.getName());
    }

    @Test
    @DisplayName("SELF-01 common 依赖契约层的规则必须能报错")
    void commonLayerRuleMustDetectViolation() {
        assertRuleDetects(ArchitectureRules.commonMustNotDependOnUpperLayers().get(0), "CommonDependsOnApiFixture");
    }

    @Test
    @DisplayName("SELF-02 api 依赖实现库的规则必须能报错")
    void apiLayerRuleMustDetectViolation() {
        assertRuleDetects(ArchitectureRules.apiMustNotDependOnImplementations().get(0), "ApiDependsOnCoreFixture");
    }

    @Test
    @DisplayName("SELF-03 实现库依赖部署单元的规则必须能报错")
    void implementationLayerRuleMustDetectViolation() {
        assertRuleDetects(
                ArchitectureRules.implementationsMustNotDependOnApplications().get(0), "CoreDependsOnBusinessFixture");
    }

    @Test
    @DisplayName("SELF-04 printStackTrace 规则必须能报错（不能按 owner 精确匹配）")
    void printStackTraceRuleMustDetectViolation() {
        assertRuleDetects(ArchitectureRules.codingRules().get(0), "printStackTrace");
    }

    @Test
    @DisplayName("SELF-05 System.out 规则必须能报错")
    void systemOutRuleMustDetectViolation() {
        assertRuleDetects(ArchitectureRules.codingRules().get(1), "System.out");
    }

    @Test
    @DisplayName("SELF-06 Collections.emptyList 规则必须能报错")
    void collectionsEmptyListRuleMustDetectViolation() {
        assertRuleDetects(ArchitectureRules.codingRules().get(2), "emptyList");
    }

    @Test
    @DisplayName("SELF-07 合规样本不得被误报（只有「违规必红」不足以证明规则写对）")
    void rulesShouldStaySilentOnCompliantClasses() {
        JavaClasses compliant = new ClassFileImporter()
                .importClasses(CommonCompliantFixture.class, ApiMarker.class, CoreMarker.class);
        for (ArchRule rule : ArchitectureRules.allRules()) {
            assertThat(rule.evaluate(compliant).hasViolation())
                    .as("规则 [%s] 在合规样本上误报（恒红的规则同样是坏规则）", rule.getDescription())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("SELF-08 空选择集必须被显式暴露（防止自检因 ArchUnit 的空集报错而假绿）")
    void emptySelectionMustBeReportedAsBrokenSelfCheck() {
        ArchRule bogus = noClasses()
                .that().resideInAPackage("cn.ypbin.iotcloud.nonexistent..")
                .should().dependOnClassesThat().resideInAnyPackage(ArchitectureRules.API)
                .because("合成规则：目标包不存在，用于验证空集不会被当成通过");
        assertThatThrownBy(() -> assertRuleDetects(bogus, "任何东西"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("一个类都没评估到");
    }

    /**
     * 断言规则在夹具上真的报出了违规。
     *
     * @param rule             规则
     * @param expectedFragment 报错内容里必须出现的片段（夹具类名或违规方法名）
     */
    private static void assertRuleDetects(ArchRule rule, String expectedFragment) {
        String report = failureReportOf(rule);
        assertThat(report)
                .as("规则 [%s] 未命中合成违规样本；报错内容如下：%n%s", rule.getDescription(), report)
                .contains(expectedFragment)
                .doesNotContain(EMPTY_SHOULD_MARKER);
    }

    /**
     * 取规则的违规报告；空选择集（规则没评估到任何类）一律视为自检失效并抛出。
     *
     * @param rule 规则
     * @return 违规报告文本
     */
    private static String failureReportOf(ArchRule rule) {
        try {
            return rule.evaluate(allClasses).getFailureReport().toString();
        } catch (AssertionError emptySelection) {
            throw new AssertionError(
                    "规则 [" + rule.getDescription() + "] 一个类都没评估到 —— 夹具包名与规则目标包不匹配，"
                            + "这条自检是假绿（ArchUnit 空集报错原文： " + emptySelection.getMessage() + "）",
                    emptySelection);
        }
    }
}
