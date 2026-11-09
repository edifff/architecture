package ru.archlabs.chat;

import ru.archlabs.chat.api.ChatFacades;
import ru.archlabs.chat.api.GrpcChatGateway;
import ru.archlabs.chat.http.ChatHttpServer;
import ru.archlabs.chat.metrics.MetricsHttpServer;

/**
 * Lab 5 input container: Netty HTTP/SSE and a gRPC client to the chat layer.
 */
public final class ChatApplication {
    private ChatApplication() {
    }

    public static void main(String[] args) throws Exception {
        var chatHost = System.getenv().getOrDefault("CHAT_HOST", "localhost");
        var chatPort = intEnv("CHAT_PORT", 9091);
        var httpPort = intEnv("ARCHLABS_HTTP_PORT", 8080);
        var metricsPort = intEnv("ARCHLABS_METRICS_PORT", 9100);

        var gateway = new GrpcChatGateway(chatHost, chatPort);
        var http = new ChatHttpServer(ChatFacades.over(gateway), httpPort);
        var metrics = new MetricsHttpServer(gateway.metrics(), metricsPort);
        http.start();
        metrics.start();
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    http.close();
                                    metrics.close();
                                    gateway.close();
                                }));

        System.out.println("HTTP: " + http.baseUri());
        System.out.println("metrics: http://localhost:" + metrics.port() + "/metrics");
        System.out.println("chat target: " + chatHost + ":" + chatPort);
        Thread.currentThread().join();
    }

    private static int intEnv(String name, int fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }
}
