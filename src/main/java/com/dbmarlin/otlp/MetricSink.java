package com.dbmarlin.otlp;

public interface MetricSink {
    void accept(MetricPoint point);
}
