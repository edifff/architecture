package ru.archlabs.chat.metrics;

import io.prometheus.metrics.config.EscapingScheme;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Histogram;
import io.prometheus.metrics.expositionformats.ExpositionFormats;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public final class RpcMetrics {
    private static final ExpositionFormats EXPOSITION = ExpositionFormats.init();

    private final PrometheusRegistry registry = new PrometheusRegistry();
    private final Counter calls =
            Counter.builder()
                    .name("rpc_calls")
                    .help("gRPC calls by service, method and status")
                    .labelNames("service", "method", "status")
                    .register(registry);
    private final Histogram latency =
            Histogram.builder()
                    .name("rpc_latency_seconds")
                    .help("gRPC call latency")
                    .labelNames("service", "method")
                    .register(registry);

    public void call(String service, String method, String status, long nanos) {
        calls.labelValues(service, method, status).inc();
        latency.labelValues(service, method).observe(nanos / 1_000_000_000.0);
    }

    public String scrape() {
        var out = new ByteArrayOutputStream();
        try {
            EXPOSITION.getPrometheusTextFormatWriter()
                    .write(out, registry.scrape(), EscapingScheme.DEFAULT);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot render RPC metrics", exception);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
