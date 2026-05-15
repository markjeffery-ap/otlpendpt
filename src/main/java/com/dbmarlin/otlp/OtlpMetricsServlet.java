package com.dbmarlin.otlp;

import com.google.protobuf.InvalidProtocolBufferException;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.Gauge;
import io.opentelemetry.proto.metrics.v1.Histogram;
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.metrics.v1.Sum;
import io.opentelemetry.proto.resource.v1.Resource;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

public class OtlpMetricsServlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(OtlpMetricsServlet.class.getName());

    private final MetricSink metricSink = new LoggingMetricSink();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        try {
            byte[] body = readBody(request);

            ExportMetricsServiceRequest otlpRequest =
                    ExportMetricsServiceRequest.parseFrom(body);

            int accepted = processMetrics(otlpRequest);

            LOG.info("Accepted OTLP metrics request. Metric points processed=" + accepted);

            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/x-protobuf");

            ExportMetricsServiceResponse otlpResponse =
                    ExportMetricsServiceResponse.newBuilder().build();

            otlpResponse.writeTo(response.getOutputStream());

        } catch (InvalidProtocolBufferException e) {
            LOG.warning("Invalid OTLP protobuf payload: " + e.getMessage());
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid OTLP protobuf payload");

        } catch (Exception e) {
            LOG.severe("Failed to process OTLP metrics payload: " + e.getMessage());
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to process OTLP metrics");
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/plain");
        response.getWriter().println("DBmarlin OTLP metrics endpoint is running");
    }

    private int processMetrics(ExportMetricsServiceRequest request) {
        int count = 0;

        for (ResourceMetrics resourceMetrics : request.getResourceMetricsList()) {
            Map<String, String> resourceAttrs =
                    attributesFromResource(resourceMetrics.getResource());

            for (ScopeMetrics scopeMetrics : resourceMetrics.getScopeMetricsList()) {
                for (Metric metric : scopeMetrics.getMetricsList()) {
                    count += processMetric(metric, resourceAttrs);
                }
            }
        }

        return count;
    }

    private int processMetric(Metric metric, Map<String, String> resourceAttrs) {
        switch (metric.getDataCase()) {
            case GAUGE:
                return processGauge(metric, metric.getGauge(), resourceAttrs);

            case SUM:
                return processSum(metric, metric.getSum(), resourceAttrs);

            case HISTOGRAM:
                return processHistogram(metric, metric.getHistogram(), resourceAttrs);

            default:
                LOG.info("Ignoring unsupported metric type: " + metric.getName()
                        + " type=" + metric.getDataCase());
                return 0;
        }
    }

    private int processGauge(Metric metric, Gauge gauge, Map<String, String> resourceAttrs) {
        int count = 0;

        for (NumberDataPoint point : gauge.getDataPointsList()) {
            metricSink.accept(toMetricPoint(metric, "gauge", point, resourceAttrs));
            count++;
        }

        return count;
    }

    private int processSum(Metric metric, Sum sum, Map<String, String> resourceAttrs) {
        int count = 0;

        for (NumberDataPoint point : sum.getDataPointsList()) {
            metricSink.accept(toMetricPoint(metric, "sum", point, resourceAttrs));
            count++;
        }

        return count;
    }

    private int processHistogram(Metric metric, Histogram histogram, Map<String, String> resourceAttrs) {
        int count = 0;

        for (HistogramDataPoint point : histogram.getDataPointsList()) {
            MetricPoint mp = new MetricPoint();
            mp.name = metric.getName();
            mp.description = metric.getDescription();
            mp.unit = metric.getUnit();
            mp.type = "histogram";
            mp.timestamp = timestamp(point.getTimeUnixNano());
            mp.attributes.putAll(resourceAttrs);
            mp.attributes.putAll(attributesFromList(point.getAttributesList()));
            mp.value = point.getSum();
            mp.count = point.getCount();

            metricSink.accept(mp);
            count++;
        }

        return count;
    }

    private MetricPoint toMetricPoint(
            Metric metric,
            String type,
            NumberDataPoint point,
            Map<String, String> resourceAttrs) {

        MetricPoint mp = new MetricPoint();
        mp.name = metric.getName();
        mp.description = metric.getDescription();
        mp.unit = metric.getUnit();
        mp.type = type;
        mp.timestamp = timestamp(point.getTimeUnixNano());
        mp.attributes.putAll(resourceAttrs);
        mp.attributes.putAll(attributesFromList(point.getAttributesList()));

        switch (point.getValueCase()) {
            case AS_DOUBLE:
                mp.value = point.getAsDouble();
                break;
            case AS_INT:
                mp.value = point.getAsInt();
                break;
            default:
                mp.value = null;
        }

        return mp;
    }

    private Map<String, String> attributesFromResource(Resource resource) {
        return attributesFromList(resource.getAttributesList());
    }

    private Map<String, String> attributesFromList(Iterable<KeyValue> attributes) {
        Map<String, String> result = new LinkedHashMap<>();

        for (KeyValue kv : attributes) {
            result.put(kv.getKey(), valueAsString(kv.getValue()));
        }

        return result;
    }

    private String valueAsString(AnyValue value) {
        switch (value.getValueCase()) {
            case STRING_VALUE:
                return value.getStringValue();
            case BOOL_VALUE:
                return Boolean.toString(value.getBoolValue());
            case INT_VALUE:
                return Long.toString(value.getIntValue());
            case DOUBLE_VALUE:
                return Double.toString(value.getDoubleValue());
            default:
                return value.toString();
        }
    }

    private Instant timestamp(long unixNano) {
        if (unixNano <= 0) {
            return Instant.now();
        }

        long seconds = unixNano / 1_000_000_000L;
        long nanos = unixNano % 1_000_000_000L;
        return Instant.ofEpochSecond(seconds, nanos);
    }

    private byte[] readBody(HttpServletRequest request) throws IOException {
        byte[] raw = readAllBytes(request.getInputStream());

        String encoding = request.getHeader("Content-Encoding");
        if (encoding != null && encoding.equalsIgnoreCase("gzip")) {
            return gunzip(raw);
        }

        return raw;
    }

    private byte[] readAllBytes(ServletInputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];

        int read;
        while ((read = inputStream.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }

        return buffer.toByteArray();
    }

    private byte[] gunzip(byte[] data) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(data))) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];

            int read;
            while ((read = gzip.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }

            return buffer.toByteArray();
        }
    }
}
