package ru.archlabs.chat;

import io.prometheus.metrics.config.EscapingScheme;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.expositionformats.ExpositionFormats;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public final class ChatMetrics {
    private static final ExpositionFormats EXPOSITION = ExpositionFormats.init();

    private final PrometheusRegistry registry = new PrometheusRegistry();
    private final Counter requests =
            Counter.builder()
                    .name("chat_requests")
                    .help("Processed user requests")
                    .labelNames("operation")
                    .register(registry);
    private final Counter notifications =
            Counter.builder()
                    .name("chat_notifications")
                    .help("Notifications delivered to users")
                    .labelNames("channel")
                    .register(registry);

    public void requestProcessed(String operation) {
        requests.labelValues(operation).inc();
    }

    public void notificationsDelivered(String channel, int count) {
        if (count > 0) {
            notifications.labelValues(channel).inc(count);
        }
    }

    public String scrape() {
        var out = new ByteArrayOutputStream();
        try {
            EXPOSITION.getPrometheusTextFormatWriter()
                    .write(out, registry.scrape(), EscapingScheme.DEFAULT);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot render Prometheus metrics", exception);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
