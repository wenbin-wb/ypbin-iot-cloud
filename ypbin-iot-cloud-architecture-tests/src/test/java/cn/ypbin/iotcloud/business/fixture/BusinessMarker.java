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
package cn.ypbin.iotcloud.business.fixture;

/**
 * 部署单元标记类（测试夹具）。
 *
 * <p>只作为分层规则的<b>依赖目标</b>存在：违规夹具需要「部署单元里的一个类」才能构造出
 * 「实现库依赖部署单元」这种违规。</p>
 *
 * @author wenbin
 * @since 2026-09-18
 */
public class BusinessMarker {
}
