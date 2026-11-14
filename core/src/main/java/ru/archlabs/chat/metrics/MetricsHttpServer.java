package ru.archlabs.chat.metrics;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import com.sun.net.httpserver.HttpServer;

import io.prometheus.client.exporter.common.TextFormat;

/**
 * Minimal Prometheus exposition endpoint over the JDK HTTP server.
 */
public final class MetricsHttpServer implements AutoCloseable {
    private final HttpServer server;

    public MetricsHttpServer(ChatMetrics metrics, int port) throws IOException {
        Objects.requireNonNull(metrics, "metrics");
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext(
                "/metrics",
                exchange -> {
                    StringWriter writer = new StringWriter();
                    try {
                        TextFormat.write004(writer, metrics.registry().metricFamilySamples());
                    } catch (IOException exception) {
                        exchange.sendResponseHeaders(500, -1);
                        exchange.close();
                        return;
                    }
                    byte[] body = writer.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders()
                            .add("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
