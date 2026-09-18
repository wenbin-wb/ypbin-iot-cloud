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
    @DisplayName("SELF-03 自检样本必须覆盖全部四条规则与仓库根目录")
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
