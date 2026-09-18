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
package cn.ypbin.iotcloud.core.fixture;

import cn.ypbin.iotcloud.business.fixture.BusinessMarker;

/**
 * <b>故意违规的测试夹具</b>：位于实现库包下却依赖了部署单元。
 *
 * <p>用于验证「实现库不得依赖部署单元」这条规则真的会报错。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
public class CoreDependsOnBusinessFixture {

    /** 违规：实现库反向持有部署单元类型。 */
    private final BusinessMarker marker = new BusinessMarker();

    /**
     * 返回被依赖的标记对象。
     *
     * @return 部署单元标记对象
     */
    public BusinessMarker marker() {
        return marker;
    }
}
