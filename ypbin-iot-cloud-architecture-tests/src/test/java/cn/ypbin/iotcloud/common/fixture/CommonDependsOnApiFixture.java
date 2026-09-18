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

import cn.ypbin.iotcloud.api.fixture.ApiMarker;

/**
 * <b>故意违规的测试夹具</b>：位于 {@code common} 包下却依赖了契约层。
 *
 * <p>它只存在于测试源码，用于验证「common 不得依赖契约层」这条规则真的会报错。
 * 若规则写错（例如包名匹配不到），本夹具不会被捕捉，
 * {@code RuleEffectivenessTest} 就会失败。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
public class CommonDependsOnApiFixture {

    /** 违规：基础库直接持有契约层类型。 */
    private final ApiMarker marker = new ApiMarker();

    /**
     * 返回被依赖的标记对象。
     *
     * @return 契约层标记对象
     */
    public ApiMarker marker() {
        return marker;
    }
}
