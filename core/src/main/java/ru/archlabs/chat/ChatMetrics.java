package ru.archlabs.chat;

import io.prometheus.metrics.config.EscapingScheme;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.expositionformats.ExpositionFormats;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Метрики чата в формате Prometheus.
 *
 * <p>Считаются две величины:
 *
 * <ul>
 *   <li>{@code chat_requests_total\{operation\}} — сколько пользовательских запросов обработано
 *       (подключение, отключение, чтение состояния, отправка, настройки уведомлений, история);
 *   <li>{@code chat_notifications_total\{channel\}} — сколько оповещений доставлено пользователям:
 *       {@code live} — онлайн-доставка, {@code backlog} — накопленные оповещения, выданные при
 *       подключении.
 * </ul>
 *
 * <p>У каждого экземпляра собственный {@link PrometheusRegistry}, поэтому счётчики разных
 * серверов (например, в тестах) не смешиваются.
 */
public final class ChatMetrics {
    /**
     * Источник метрик. Реализуется серверной логикой, чтобы HTTP-слой мог получить тот же
     * экземпляр {@link ChatMetrics} и отдать его на {@code GET /metrics}.
     */
    public interface Source {
        ChatMetrics metrics();
    }

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

    /** Учитывает один обработанный пользовательский запрос указанного вида. */
    public void requestProcessed(String operation) {
        requests.labelValues(operation).inc();
    }

    /** Учитывает доставленные пользователям оповещения: {@code live} или {@code backlog}. */
    public void notificationsDelivered(String channel, int count) {
        if (count > 0) {
            notifications.labelValues(channel).inc(count);
        }
    }

    /** Возвращает метрики в текстовом формате Prometheus для ответа на {@code GET /metrics}. */
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
