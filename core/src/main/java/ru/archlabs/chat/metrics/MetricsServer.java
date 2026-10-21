package ru.archlabs.chat.metrics;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/** Минимальный HTTP-эндпоинт {@code /metrics} для скрейпа Prometheus (JDK HttpServer). */
public final class MetricsServer implements AutoCloseable {
    private final HttpServer server;

    public MetricsServer(int port, Supplier<String> body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext(
                "/metrics",
                exchange -> {
                    var bytes = body.get().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders()
                            .set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
        server.start();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
