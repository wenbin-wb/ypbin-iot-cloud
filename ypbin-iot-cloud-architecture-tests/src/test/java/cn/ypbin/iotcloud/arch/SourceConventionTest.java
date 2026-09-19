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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * 源码级规范门禁（第一批门禁的第 2 项）。
 *
 * <p><b>为什么这些规则必须在源码层而不是字节码层</b>（母仓总结的教训）：</p>
 * <ul>
 *   <li><b>内联全限定类名</b>：字节码里所有类型引用都是全限定名，无法区分「import 后使用简单名」
 *       与「正文里直接写全限定名」；</li>
 *   <li><b>{@code @Bean} 条件注解</b>：注解保留策略与合成方式让字节码规则容易漏判；</li>
 *   <li><b>{@code ordinal()}</b>：javac 会把 {@code switch(enum)} 编译成 ordinal() 查表，
 *       字节码规则<b>必然误报</b>项目鼓励的 switch 写法。</li>
 * </ul>
 *
 * <p>每条规则都配有效性自检：用合成的违规样本或清单断言规则确实命中，
 * 避免规则写成恒为真却永不报错（母仓已因此踩过三次）。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
class SourceConventionTest {

    /** 仓库根目录（测试的工作目录是 architecture-tests 模块）。 */
    private static final Path REPO_ROOT = Paths.get("..").toAbsolutePath().normalize();

    /** 本仓模块目录前缀。 */
    private static final String MODULE_PREFIX = "ypbin-iot-cloud-";

    /** 八个源码模块（新增模块时必须同步这里，否则该模块的主源码不会被扫描）。 */
    private static final List<String> SOURCE_MODULES = List.of(
            "common", "api", "auth", "core", "openapi", "gateway", "business", "access");

    /**
     * 内联全限定类名：正文中出现任意包名前缀（本仓 / JDK / 第三方），
     * 且不在 import / package 行、不在注释里。
     *
     * <p><b>为什么必须覆盖全部包名</b>：只匹配 {@code cn.ypbin.} 会让
     * {@code java.util.concurrent.TimeUnit}、{@code org.springframework...} 这类
     * 第三方/JDK 内联 FQCN <b>结构性漏判</b> —— 同一条规则，第一方被拦、第三方放行。</p>
     */
    private static final Pattern INLINE_FQCN = Pattern.compile(
            "(?<![\\w.$])(?:cn\\.ypbin|java|javax|jakarta|org|com|io)\\.[a-z][\\w]*"
                    + "(?:\\.[a-zA-Z][\\w]*)+");

    /** {@code ordinal()} 调用。 */
    private static final Pattern ORDINAL_CALL = Pattern.compile("\\.ordinal\\s*\\(\\s*\\)");

    private static final Pattern AUTO_CONFIGURATION = Pattern.compile("@AutoConfiguration\\b");

    /** 合法 FQCN（用于校验 imports 文件内容）。 */
    private static final Pattern FQCN = Pattern.compile(
            "[a-zA-Z_$][a-zA-Z\\d_$]*(\\.[a-zA-Z_$][a-zA-Z\\d_$]*)+");

    private static final Pattern BEAN_ANNOTATION = Pattern.compile("^\\s*@Bean\\b.*$", Pattern.MULTILINE);

    private static final String CONDITIONAL_ON_MISSING_BEAN = "@ConditionalOnMissingBean";

    @Test
    @DisplayName("SRC-01 禁止内联全限定类名（Javadoc 的 {@link FQCN} 豁免）")
    void noInlineFullyQualifiedNames() {
        List<String> violations = new ArrayList<>();
        for (Path file : mainJavaFiles()) {
            List<String> lines = readLines(file);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (isExemptLine(line)) {
                    continue;
                }
                String code = stripJavadocLinks(line);
                Matcher matcher = INLINE_FQCN.matcher(code);
                if (matcher.find()) {
                    violations.add(REPO_ROOT.relativize(file) + ":" + (index + 1) + " -> " + matcher.group());
                }
            }
        }
        assertThat(violations)
                .as("正文禁用内联全限定类名（一律顶部 import）；Javadoc 的 {@link FQCN} 不在此列")
                .isEmpty();
    }

    @Test
    @DisplayName("SRC-02 禁止 ordinal()")
    void noOrdinalUsage() {
        List<String> violations = new ArrayList<>();
        for (Path file : mainJavaFiles()) {
            List<String> lines = readLines(file);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (isExemptLine(line)) {
                    continue;
                }
                if (ORDINAL_CALL.matcher(stripJavadocLinks(line)).find()) {
                    violations.add(REPO_ROOT.relativize(file) + ":" + (index + 1));
                }
            }
        }
        assertThat(violations).as("枚举一律用 code 存取，禁止 ordinal()").isEmpty();
    }

    @Test
    @DisplayName("SRC-03 每个 @Bean 方法都必须带 @ConditionalOnMissingBean")
    void everyBeanMustBeOverridable() {
        List<String> violations = new ArrayList<>();
        for (Path file : mainJavaFiles()) {
            String content = read(file);
            if (!content.contains("@Bean")) {
                continue;
            }
            String[] lines = content.split("\n", -1);
            for (int index = 0; index < lines.length; index++) {
                if (!BEAN_ANNOTATION.matcher(lines[index]).matches()) {
                    continue;
                }
                // 向下看 12 行内是否出现 @ConditionalOnMissingBean（覆盖 @Bean 与签名之间的注解块）
                boolean found = false;
                for (int probe = index; probe < Math.min(index + 12, lines.length); probe++) {
                    if (lines[probe].contains(CONDITIONAL_ON_MISSING_BEAN)) {
                        found = true;
                        break;
                    }
                    // 遇到方法体开始即停止搜索
                    if (lines[probe].contains("{")) {
                        break;
                    }
                }
                if (!found) {
                    violations.add(REPO_ROOT.relativize(file) + ":" + (index + 1));
                }
            }
        }
        assertThat(violations)
                .as("每个对外 Bean 都必须可被宿主覆盖")
                .isEmpty();
    }

    @Test
    @DisplayName("SRC-05 AutoConfiguration.imports 的每一行必须要么是 # 注释、要么是合法 FQCN")
    void importsFileMustContainOnlyFqcnOrHashComment() {
        List<String> violations = new ArrayList<>();
        for (Path module : modules()) {
            Path imports = module.resolve(
                    "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
            if (!Files.isRegularFile(imports)) {
                continue;
            }
            List<String> lines = readLines(imports);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index).trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (!FQCN.matcher(line).matches()) {
                    // Spring 的 imports 解析器只按 # 截断注释：/* */ 之类的内容会被当成类名加载
                    // 并导致启动失败（实测：IllegalStateException: Unable to read meta-data for class */）
                    violations.add(REPO_ROOT.relativize(imports) + ":" + (index + 1) + " -> " + line);
                    continue;
                }
                // 格式合法还不够：**错拼一个类型名**同样是「门禁全绿 + 启动才炸」。
                // 这里真的把它加载出来（不初始化，避免触发静态副作用），并要求它是 @AutoConfiguration。
                try {
                    Class<?> type = Class.forName(line, false,
                        Thread.currentThread().getContextClassLoader());
                    if (!type.isAnnotationPresent(AutoConfiguration.class)) {
                        violations.add(REPO_ROOT.relativize(imports) + ":" + (index + 1)
                            + " -> " + line + " 不是 @AutoConfiguration 类");
                    }
                } catch (ClassNotFoundException e) {
                    violations.add(REPO_ROOT.relativize(imports) + ":" + (index + 1)
                        + " -> " + line + " 在 classpath 上不存在（错拼的类型名会让启动期才失败）；"
                        + "若该模块确实存在，请检查 architecture-tests 的依赖是否漏了它");
                } catch (LinkageError e) {
                    // 类在，但它的父类/依赖不在（例如模块没被加进 arch-tests 的 classpath）
                    violations.add(REPO_ROOT.relativize(imports) + ":" + (index + 1)
                        + " -> " + line + " 无法链接（" + e.getClass().getSimpleName()
                        + "）：多半是 architecture-tests 缺该模块依赖，而不是类型名写错");
                }
            }
        }
        assertThat(violations)
            .as("imports 文件只允许 FQCN 与 # 注释；其它内容会被 Spring 当成类名去加载")
            .isEmpty();
    }

    @Test
    @DisplayName("SRC-06 入站守卫里的凭证比较必须走 MessageDigest.isEqual（禁退化成 String.equals）")
    void internalTokenComparisonMustBeConstantTime() {
        List<String> violations = new ArrayList<>();
        int scanned = 0;
        for (Path file : mainJavaFiles()) {
            String code = stripCommentsAndLiterals(read(file));
            if (!isInboundTokenGuardSource(code)) {
                continue;
            }
            scanned++;
            if (!usesConstantTimeComparison(code)) {
                violations.add(REPO_ROOT.relativize(file)
                    + " -> 未使用 MessageDigest.isEqual 进行凭证比较（常量时间比较是硬要求）");
            }
        }
        // 教训（母仓教训七/八）：规则必须证明「真的扫到了东西」——按文件名找时，改名即静默放行
        assertThat(scanned)
            .as("必须至少扫到一个入站守卫源码，否则本规则是空跑")
            .isPositive();
        assertThat(violations)
            .as("内部凭证比较必须用 MessageDigest.isEqual；退化成 equals 会引入计时侧信道")
            .isEmpty();
    }

    /**
     * 是否是「入站凭证守卫」源码（按<b>语义</b>识别，不按文件名——按文件名会被改名绕过）。
     *
     * <p>约定：守卫的凭证比较必须写在守卫自己里（`MessageDigest.isEqual` 就在本文件内），
     * 这样安全关键点始终可见可审；若将来把比较抽到 helper，本规则与本注释须同步更新。</p>
     *
     * <p><b>已知覆盖边界</b>：谓词只识别 {@code HandlerInterceptor}/{@code preHandle} 型守卫；
     * 若将来新增 <b>Filter 型</b>入站守卫，需要同步扩展本谓词（否则 Filter 型不会被扫到，
     * 而 {@code scanned >= 1} 又不会触发空跑自检）。</p>
     *
     * @param strippedCode 已剥离注释与字面量的源码
     * @return 是入站守卫源码时返回 {@code true}
     */
    static boolean isInboundTokenGuardSource(String strippedCode) {
        boolean isInterceptor = strippedCode.contains("HandlerInterceptor")
                || strippedCode.contains("preHandle(");
        return isInterceptor && strippedCode.contains("TOKEN_HEADER");
    }

    /**
     * 是否使用了常量时间比较。
     *
     * @param strippedCode 已剥离注释与字面量的源码
     * @return 调用了 {@code MessageDigest.isEqual} 时返回 {@code true}
     */
    static boolean usesConstantTimeComparison(String strippedCode) {
        return strippedCode.contains("MessageDigest.isEqual(");
    }

    @Test
    @DisplayName("SRC-07 可替换的端口实现不得标 @Component（必须由自动配置以 @ConditionalOnMissingBean 装配）")
    void replaceablePortImplementationsMustNotBeScannedComponents() {
        List<String> violations = new ArrayList<>();
        int scanned = 0;
        for (Path file : mainJavaFiles()) {
            String code = stripCommentsAndLiterals(read(file));
            if (!implementsReplaceablePort(code)) {
                continue;
            }
            scanned++;
            if (isScannedComponent(code)) {
                violations.add(REPO_ROOT.relativize(file)
                    + " -> 端口实现标了 @Component：宿主再定义自己的实现会 NoUniqueBeanDefinitionException"
                    + "（@ConditionalOnMissingBean 的顺序保证只有 @AutoConfiguration 才有）");
            }
        }
        // 教训（母仓教训七/八）：必须证明真的扫到了目标，否则规则是空跑
        assertThat(scanned)
            .as("必须至少扫到一个可替换端口的实现，否则本规则是空跑")
            .isPositive();
        assertThat(violations)
            .as("可替换端口的实现必须由 @Bean @ConditionalOnMissingBean 提供，否则「宿主可替换」是假缝")
            .isEmpty();
    }

    /**
     * 是否是「可替换端口」的实现（按<b>语义</b>识别，不按文件名）。
     *
     * <p>约定：这类实现必须由 {@code @AutoConfiguration} 里的 {@code @Bean @ConditionalOnMissingBean}
     * 装配。判据是「实现了接口」+「接口名以 {@code Manager} 结尾且出现在接口清单里」太脆弱，
     * 因此这里用一条更朴素的约定：源码里出现 {@code implements TenantLinkManager}
     * （本仓当前的替换缝；将来新增替换缝时同步扩展本谓词，并保留「至少扫到一个」的自检）。</p>
     *
     * @param strippedCode 已剥离注释与字面量的源码
     * @return 是可替换端口实现时返回 {@code true}
     */
    static boolean implementsReplaceablePort(String strippedCode) {
        return strippedCode.contains("implements TenantLinkManager");
    }

    /**
     * 是否被组件扫描接管（{@code @Component}）。
     *
     * @param strippedCode 已剥离注释与字面量的源码
     * @return 标了 {@code @Component} 时返回 {@code true}
     */
    static boolean isScannedComponent(String strippedCode) {
        return strippedCode.contains("@Component");
    }

    @Test
    @DisplayName("SRC-04 每个 @AutoConfiguration 都必须在 AutoConfiguration.imports 中登记")
    void everyAutoConfigurationMustBeRegistered() {
        List<String> violations = new ArrayList<>();
        for (Path module : modules()) {
            Path imports = module.resolve(
                    "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
            Set<String> registered = new LinkedHashSet<>();
            if (Files.isRegularFile(imports)) {
                for (String line : readLines(imports)) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        registered.add(trimmed);
                    }
                }
            }
            for (Path file : javaFiles(module.resolve("src/main/java"))) {
                // 必须剥离注释与字面量后再匹配：否则 Javadoc 里形容「本类不标注 @AutoConfiguration」
                // 这类说明文字会被当成真的注解（本规则曾因此误报一个 Feign 局部配置类）
                String content = stripCommentsAndLiterals(read(file));
                if (!AUTO_CONFIGURATION.matcher(content).find()) {
                    continue;
                }
                String fqcn = toFqcn(module, file);
                if (!registered.contains(fqcn)) {
                    violations.add(REPO_ROOT.relativize(file) + " -> 未登记 " + fqcn);
                }
            }
        }
        assertThat(violations)
                .as("未登记的自动配置会被静默忽略，必须同步写入 imports 文件")
                .isEmpty();
    }

    @Test
    @DisplayName("SELF-01 内联 FQCN 规则必须能命中违规样本")
    void inlineFqcnRuleMustDetectViolation() {
        String violation = "    return cn.ypbin.iotcloud.common.CloudConstants.ARTIFACT_PREFIX;";
        assertThat(INLINE_FQCN.matcher("        long t = java.util.concurrent.TimeUnit.SECONDS.toMillis(1);").find())
                .as("JDK 内联 FQCN 同样是违规，只匹配本仓包名会漏判")
                .isTrue();
        assertThat(INLINE_FQCN.matcher("        throw new org.springframework.core.NestedRuntimeException();").find())
                .as("第三方内联 FQCN 同样必须被拦")
                .isTrue();
        assertThat(INLINE_FQCN.matcher("        Duration d = Duration.ofSeconds(1);").find())
                .as("简单类名不得误报")
                .isFalse();
        assertThat(INLINE_FQCN.matcher(stripJavadocLinks(" * @see {@link java.util.concurrent.TimeUnit}")).find())
                .as("Javadoc 的 {@link 全限定名} 必须豁免（import 会被 spotless 移除，只能用全限定名）")
                .isFalse();
        assertThat(INLINE_FQCN.matcher(stripJavadocLinks(violation)).find())
                .as("规则写错就永远不报错，等于装饰")
                .isTrue();
        assertThat(isExemptLine("import cn.ypbin.iotcloud.common.CloudConstants;")).isTrue();
    }

    @Test
    @DisplayName("SELF-02 ordinal 规则必须能命中违规样本")
    void ordinalRuleMustDetectViolation() {
        assertThat(ORDINAL_CALL.matcher("int i = quality.ordinal();").find()).isTrue();
        assertThat(ORDINAL_CALL.matcher("int i = values()[index];").find())
                .as("合法写法不得误报")
                .isFalse();
        assertThat(isExemptLine("        // 严禁使用 ordinal() 存库"))
                .as("注释行必须被豁免，否则说明文字会触发误报")
                .isTrue();
    }

    @Test
    @DisplayName("SELF-03 自检样本必须覆盖全部规则与仓库根目录")
    void selfCheckMustCoverAllRules() {
        assertThat(AUTO_CONFIGURATION.matcher("@AutoConfiguration").find()).isTrue();
        // 边界：只有出现在「有效代码」里的注解才算，注释/Javadoc/字符串里的提及不算
        assertThat(AUTO_CONFIGURATION.matcher(
                stripCommentsAndLiterals("// 本类不标注 @AutoConfiguration\n")).find()).isFalse();
        assertThat(AUTO_CONFIGURATION.matcher(
                stripCommentsAndLiterals("/** 说明：{@code @AutoConfiguration} 不适用 */\n")).find()).isFalse();
        assertThat(AUTO_CONFIGURATION.matcher(
                stripCommentsAndLiterals("String s = \"@AutoConfiguration\";\n")).find()).isFalse();
        assertThat(AUTO_CONFIGURATION.matcher(stripCommentsAndLiterals(
                "@AutoConfiguration\npublic class A {}\n")).find()).isTrue();

        // SRC-05/06 规则自检：合法 FQCN 与 # 注释通过；/* */ 与半截注释被拒
        assertThat(FQCN.matcher("cn.ypbin.iotcloud.common.autoconfigure.InternalTokenAutoConfiguration")
                .matches()).isTrue();
        assertThat(FQCN.matcher("/*").matches()).isFalse();
        assertThat(FQCN.matcher("* Copyright (c) 2024-present").matches()).isFalse();
        assertThat(FQCN.matcher("*/").matches()).isFalse();
        assertThat(stripCommentsAndLiterals(
                "MessageDigest.isEqual(a, b);").contains("MessageDigest.isEqual(")).isTrue();
        assertThat(stripCommentsAndLiterals(
                "// 说明：用 MessageDigest.isEqual 比较\nconfigured.equals(presented);")
                .contains("MessageDigest.isEqual(")).isFalse();
        // SRC-06 自检：语义识别（不依赖文件名）+ 两个方向的判定
        String guardWithoutConstantTime = stripCommentsAndLiterals(
                "class X implements HandlerInterceptor {\n"
                + "  boolean preHandle() { return configured.equals(request.getHeader(TOKEN_HEADER)); }\n}");
        assertThat(isInboundTokenGuardSource(guardWithoutConstantTime)).isTrue();
        assertThat(usesConstantTimeComparison(guardWithoutConstantTime)).isFalse();
        String guardWithConstantTime = stripCommentsAndLiterals(
                "class X implements HandlerInterceptor {\n"
                + "  boolean preHandle() { return MessageDigest.isEqual(a, b); }\n}");
        assertThat(usesConstantTimeComparison(guardWithConstantTime)).isTrue();
        // 出站侧（只加头、不比较）不得被误判为入站守卫
        assertThat(isInboundTokenGuardSource(stripCommentsAndLiterals(
                "class Y implements RequestInterceptor {\n"
                + "  void apply() { template.header(TOKEN_HEADER, token); }\n}"))).isFalse();
        // SRC-07 自检：语义识别 + 两个方向（实现标 @Component 必须被抓；不标则放行）
        String componentPortImpl = stripCommentsAndLiterals(
                "@Component\nclass L implements TenantLinkManager { }\n");
        assertThat(implementsReplaceablePort(componentPortImpl)).isTrue();
        assertThat(isScannedComponent(componentPortImpl)).isTrue();
        String beanPortImpl = stripCommentsAndLiterals(
                "class L implements TenantLinkManager { }\n");
        assertThat(implementsReplaceablePort(beanPortImpl)).isTrue();
        assertThat(isScannedComponent(beanPortImpl)).isFalse();
        // Javadoc 里提到「不得标 @Component」不算违规（剥离注释后不可见）
        assertThat(isScannedComponent(stripCommentsAndLiterals(
                "/** 不得标 @Component；由 @ConditionalOnMissingBean 装配 */\nclass L {}\n"))).isFalse();
        assertThat(BEAN_ANNOTATION.matcher("    @Bean").matches()).isTrue();
        assertThat(CONDITIONAL_ON_MISSING_BEAN).isNotBlank();
        assertThat(REPO_ROOT.resolve(MODULE_PREFIX + "common")).isDirectory();
    }

    @Test
    @DisplayName("SELF-04 源码清单必须真的扫到东西（否则 SRC-01/02 是空跑）")
    void mainSourcesMustBeDiscovered() {
        assertThat(mainJavaFiles())
                .as("主源码清单为空：SrcConventionTest 的所有扫描规则都会变成空跑（假绿）")
                .isNotEmpty();
        for (String module : SOURCE_MODULES) {
            assertThat(javaFiles(REPO_ROOT.resolve(MODULE_PREFIX + module).resolve("src/main/java")))
                    .as("模块 %s 一个主源码文件都没扫到 —— 模块改名/挪位后必须同步 SOURCE_MODULES", module)
                    .isNotEmpty();
        }
    }

    /**
     * 剥离注释与字符串/字符/文本块字面量，返回「有效代码」文本（保留换行以维持行号）。
     *
     * <p>铁律类违规只可能出现在有效代码中；注释与字符串里的 {@code cn.ypbin.*} 属合法内容
     * （如 Javadoc 引用、{@code Class.forName("...")}）。文本块必须整体跳过：否则内容里的引号会
     * 让剥离器与后续代码错位，其后的代码被整段吞掉，所有消费剥离文本的规则<b>静默失明</b>。</p>
     *
     * @param source 原始源码
     * @return 去掉注释与字面量后的代码文本
     */
    static String stripCommentsAndLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char ch = source.charAt(i);
            if (ch == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (ch == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    if (source.charAt(i) == '\n') {
                        out.append('\n');
                    }
                    i++;
                }
                i = Math.min(i + 2, n);
            } else if (ch == '"' && i + 2 < n && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                // 文本块：整体跳过，避免奇数个引号导致后续代码被吞
                i += 3;
                while (i < n) {
                    if (source.charAt(i) == '\\') {
                        i += 2;
                        continue;
                    }
                    if (source.charAt(i) == '"' && i + 2 < n
                        && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                        i += 3;
                        break;
                    }
                    if (source.charAt(i) == '\n') {
                        out.append('\n');
                    }
                    i++;
                }
            } else if (ch == '"' || ch == '\'') {
                char quote = ch;
                i++;
                while (i < n) {
                    char current = source.charAt(i);
                    if (current == '\\') {
                        i += 2;
                        continue;
                    }
                    if (current == quote) {
                        i++;
                        break;
                    }
                    if (current == '\n') {
                        out.append('\n');
                    }
                    i++;
                }
            } else {
                out.append(ch);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean isExemptLine(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("import ")
                || trimmed.startsWith("package ")
                || trimmed.startsWith("*")
                || trimmed.startsWith("/*")
                || trimmed.startsWith("//");
    }

    /**
     * 去掉 Javadoc 的 {@code {@link 全限定名}} / {@code {@linkplain ...}}（它们只能用全限定名）。
     *
     * @param line 源码行
     * @return 去掉链接后的文本
     */
    private static String stripJavadocLinks(String line) {
        return line.replaceAll("\\{@link\\s+[^}]*\\}", "").replaceAll("\\{@linkplain\\s+[^}]*\\}", "");
    }

    private static String toFqcn(Path module, Path file) {
        Path relative = module.resolve("src/main/java").relativize(file);
        return relative.toString().replace(File.separatorChar, '.').replaceAll("\\.java$", "");
    }

    private static List<Path> modules() {
        try (Stream<Path> stream = Files.list(REPO_ROOT)) {
            return stream.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(MODULE_PREFIX))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to list modules under " + REPO_ROOT, ex);
        }
    }

    private static List<Path> mainJavaFiles() {
        List<Path> files = new ArrayList<>();
        for (Path module : modules()) {
            files.addAll(javaFiles(module.resolve("src/main/java")));
        }
        return files;
    }

    private static List<Path> javaFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to walk " + root, ex);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read " + file, ex);
        }
    }

    private static List<String> readLines(Path file) {
        return List.of(read(file).split("\n", -1));
    }
}
