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
package cn.ypbin.iotcloud.common.fixture;

/**
 * <b>合规样本</b>：位于 {@code common} 包下且<b>不</b>依赖任何上层模块。
 *
 * <p>用途：{@code RuleEffectivenessTest} 用它验证分层规则不会对合规代码误报
 * （只有「违规必红」而没有「合规必绿」的自检，无法区分规则写对与规则恒红）。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
public class CommonCompliantFixture {

    /**
     * 合规：不触发任何分层规则。
     *
     * @return 常量文本
     */
    public String describe() {
        return "common fixture";
    }
}
