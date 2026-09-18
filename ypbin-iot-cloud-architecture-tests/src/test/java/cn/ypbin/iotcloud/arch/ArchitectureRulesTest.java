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

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 架构约束测试：对<b>主源码</b>执行全部规则，要求通过。
 *
 * <p>覆盖第一批门禁的三件事：分层依赖不可逆、编码铁律（字节码级部分）。规则本身的正确性由
 * {@link RuleEffectivenessTest} 用故意违规的夹具反向验证 —— ArchUnit 规则很容易写成恒为真
 * 而永不报错，没有反向验证的架构测试会给出虚假安全感。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
class ArchitectureRulesTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importMainClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("cn.ypbin.iotcloud");
    }

    @Test
    @DisplayName("ARCH-00 骨架自检：八个模块的包都必须出现在 ArchUnit 视野里")
    void everyModulePackageMustBeVisibleToArchUnit() {
        // 为什么必须有这条：不在 classpath 上的模块会被 importPackages **静默跳过**，
        // 针对它的规则看起来通过、实则从未检查过任何类（母仓教训八「0 违规也可能是没跑到」）。
        // 本仓还多一层：ArchUnit 1.5.0 默认 archRule.failOnEmptyShould=true，
        // 规则选中 0 个类会直接抛错 —— 因此这条断言也是给那个报错提供一眼可读的原因。
        assertThat(classes).as("主源码一个类都没导入").isNotEmpty();
        List<String> expectedPackages = Stream.concat(
                        ArchitectureRules.libraryPackages().stream(),
                        ArchitectureRules.applicationPackages().stream())
                .map(qualified -> qualified.substring(0, qualified.length() - "..".length()))
                .toList();
        for (String expected : expectedPackages) {
            assertThat(classes.stream().map(JavaClass::getPackageName))
                    .as("模块包 %s 在 ArchUnit 视野里一个类都没有：该模块多半没进 architecture-tests 的 classpath", expected)
                    .anyMatch(name -> name.equals(expected) || name.startsWith(expected + "."));
        }
    }

    @Test
    @DisplayName("ARCH-01 common 不得依赖契约层、实现库与部署单元")
    void commonMustNotDependOnUpperLayers() {
        check(ArchitectureRules.commonMustNotDependOnUpperLayers());
    }

    @Test
    @DisplayName("ARCH-02 api 不得依赖实现库与部署单元")
    void apiMustNotDependOnImplementations() {
        check(ArchitectureRules.apiMustNotDependOnImplementations());
    }

    @Test
    @DisplayName("ARCH-03 实现库不得依赖部署单元")
    void implementationsMustNotDependOnApplications() {
        check(ArchitectureRules.implementationsMustNotDependOnApplications());
    }

    @Test
    @DisplayName("ARCH-04 编码铁律：printStackTrace / System.out|err / Collections.emptyXxx|singletonXxx")
    void codingRulesMustHold() {
        check(ArchitectureRules.codingRules());
    }

    private static void check(List<ArchRule> rules) {
        for (ArchRule rule : rules) {
            rule.check(classes);
        }
    }
}
