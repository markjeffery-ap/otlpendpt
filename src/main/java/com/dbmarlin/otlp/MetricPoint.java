package com.dbmarlin.otlp;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class MetricPoint {
    public String name;
    public String description;
    public String unit;
    public String type;
    public Instant timestamp;
    public Number value;
    public Long count;

    public Map<String, String> attributes = new LinkedHashMap<>();
}
