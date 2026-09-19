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

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.starter.gateway.auth.GatewayAuthProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

/**
 * 「网关必须自带认证器」的门禁。
 *
 * <p><b>为什么需要它</b>：starter 在 {@code ypbin.gateway.auth.enabled=true} 但容器里没有
 * {@link GatewayAuthProvider} 时，只打一条 WARN 并<b>不注册鉴权过滤器</b>（fail-open）。
 * 也就是说「配置看起来开着鉴权、实际全部放行」不会有任何构建失败。本测试把这个洞变成门禁：
 * 一旦有人删掉 Provider，它立刻转红。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
class GatewayAuthProviderPresenceTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java");

    @Test
    @DisplayName("gateway 模块必须声明一个 @Component 的 GatewayAuthProvider 实现")
    void gatewayMustDeclareAuthProviderComponent() throws IOException {
        assertThat(Files.isDirectory(MAIN_SOURCES))
            .as("测试从模块根目录运行，找不到 src/main/java 说明工作目录不对")
            .isTrue();

        List<Class<?>> providers;
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            providers = files.filter(path -> path.toString().endsWith(".java"))
                .filter(this::implementsGatewayAuthProvider)
                .map(this::load)
                .toList();
        }
        assertThat(providers)
            .as("没有 GatewayAuthProvider 时 starter 会 fail-open（只打 WARN 且不注册鉴权过滤器）")
            .isNotEmpty();
        assertThat(providers)
            .as("Provider 必须能被 Spring 扫描到（@Component），否则等于没有")
            .allMatch(type -> type.isAnnotationPresent(Component.class));
    }

    private boolean implementsGatewayAuthProvider(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8)
                .contains("implements GatewayAuthProvider");
        } catch (IOException e) {
            throw new IllegalStateException("读取源码失败：" + path, e);
        }
    }

    private Class<?> load(Path path) {
        String relative = MAIN_SOURCES.relativize(path).toString();
        String fqcn = relative.substring(0, relative.length() - ".java".length())
            .replace(java.io.File.separatorChar, '.');
        try {
            return Class.forName(fqcn, false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("加载失败：" + fqcn, e);
        }
    }
}
