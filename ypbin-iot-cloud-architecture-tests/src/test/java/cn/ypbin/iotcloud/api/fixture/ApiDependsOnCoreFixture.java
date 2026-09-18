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
package cn.ypbin.iotcloud.api.fixture;

import cn.ypbin.iotcloud.core.fixture.CoreMarker;

/**
 * <b>故意违规的测试夹具</b>：位于 {@code api} 包下却依赖了实现库。
 *
 * <p>用于验证「api 不得依赖实现库」这条规则真的会报错。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
public class ApiDependsOnCoreFixture {

    /** 违规：契约层直接持有实现库类型。 */
    private final CoreMarker marker = new CoreMarker();

    /**
     * 返回被依赖的标记对象。
     *
     * @return 实现库标记对象
     */
    public CoreMarker marker() {
        return marker;
    }
}
