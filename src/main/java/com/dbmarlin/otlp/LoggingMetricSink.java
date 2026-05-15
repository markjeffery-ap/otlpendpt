package com.dbmarlin.otlp;

import java.util.logging.Logger;

public class LoggingMetricSink implements MetricSink {

    private static final Logger LOG = Logger.getLogger(LoggingMetricSink.class.getName());

    @Override
    public void accept(MetricPoint point) {
        LOG.info(
            "metric=" + point.name +
            ", type=" + point.type +
            ", value=" + point.value +
            ", unit=" + point.unit +
            ", timestamp=" + point.timestamp +
            ", attrs=" + point.attributes
        );
    }
}
