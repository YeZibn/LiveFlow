package com.liveflow.infrastructure.message;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 弹幕各处理阶段共用的指标命名和分位点。 */
final class RoomKafkaMetrics {
    private RoomKafkaMetrics() {}

    /**
     * @param registry 应用指标注册表
     * @param stage 固定的处理阶段标签
     * @return 对应阶段的延迟计时器
     */
    static Timer stage(MeterRegistry registry, String stage) {
        Timer timer = Timer.builder("liveflow.room.messages.stage").tag("stage", stage)
                .publishPercentiles(0.5, 0.95, 0.99).register(registry);
        for (String quantile : List.of("0.5", "0.95", "0.99")) {
            double percentile = Double.parseDouble(quantile);
            io.micrometer.core.instrument.Gauge.builder("liveflow.room.messages.stage.percentile", timer, source ->
                    java.util.Arrays.stream(source.takeSnapshot().percentileValues())
                            .filter(value -> value.percentile() == percentile).findFirst()
                            .map(value -> value.value(TimeUnit.MILLISECONDS)).orElse(Double.NaN))
                    .baseUnit("milliseconds").tags("stage", stage, "quantile", quantile).register(registry);
        }
        return timer;
    }

}
