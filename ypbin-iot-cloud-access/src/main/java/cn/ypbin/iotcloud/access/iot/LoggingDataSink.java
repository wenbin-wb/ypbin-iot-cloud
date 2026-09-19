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
package cn.ypbin.iotcloud.access.iot;

import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.spi.DataSink;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据出口的 M0a 实现：<b>只计数与打日志，不上报</b>。
 *
 * <p>为什么现在就实现它而不是留空：iot-starter 的出口链路（数据 → {@code DataEgress} → {@link DataSink}）
 * 是**有数据才会走到**的；本步 P4b 只验证「建链/断链」，所以这里明确记录收到了多少批——
 * 一旦将来有人把上报链路接上，这个计数器就是「数据确实流动了」的观测点。</p>
 *
 * <p>M0b 会把这里换成「上报到 business（含租户上下文）」，接口不变。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
public class LoggingDataSink implements DataSink {

    private static final Logger log = LoggerFactory.getLogger(LoggingDataSink.class);

    /** 指标前缀（与租约指标同域，便于一起看）。 */
    static final String METRIC_PREFIX = "iotcloud.access.";

    private final Counter batches;
    private final Counter points;

    /**
     * 构造出口。
     *
     * @param meterRegistry 指标注册表
     */
    public LoggingDataSink(MeterRegistry meterRegistry) {
        this.batches = Counter.builder(METRIC_PREFIX + "data.batches")
            .description("从设备侧收到的数据批次数").register(meterRegistry);
        this.points = Counter.builder(METRIC_PREFIX + "data.points")
            .description("从设备侧收到的点位值数量").register(meterRegistry);
    }

    @Override
    public String name() {
        return "access-m0a-logging";
    }

    @Override
    public void write(DataBatch batch) {
        int size = batch.points() == null ? 0 : batch.points().size();
        batches.increment();
        points.increment(size);
        log.debug("收到设备数据（M0a 不上报，仅计数）：deviceId={} points={}",
            batch.deviceId(), size);
    }
}
