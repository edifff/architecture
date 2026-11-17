package ru.archlabs.chat;

import ru.archlabs.chat.api.ChatFacades;
import ru.archlabs.chat.api.ChatGateway;
import ru.archlabs.chat.api.GrpcChatGateway;
import ru.archlabs.chat.api.ShardedChatGateway;
import ru.archlabs.chat.http.ChatHttpServer;
import ru.archlabs.chat.metrics.MetricsHttpServer;

/**
 * Input container: Netty HTTP/SSE over either one chat layer or a deterministic set of
 * chat shards, depending on CHAT_SHARDS.
 */
public final class ChatApplication {
    private ChatApplication() {
    }

    public static void main(String[] args) throws Exception {
        String chatHost = System.getenv().getOrDefault("CHAT_HOST", "localhost");
        int chatPort = intEnv("CHAT_PORT", 9091);
        int httpPort = intEnv("ARCHLABS_HTTP_PORT", 8080);
        int metricsPort = intEnv("ARCHLABS_METRICS_PORT", 9100);
        String shardSpec = System.getenv().getOrDefault("CHAT_SHARDS", "");

        ChatGateway gateway;
        if (shardSpec.isBlank()) {
            gateway = new GrpcChatGateway(chatHost, chatPort);
        } else {
            gateway = ShardedChatGateway.fromSpec(shardSpec);
        }
        ChatHttpServer http = new ChatHttpServer(ChatFacades.over(gateway), httpPort);
        MetricsHttpServer metrics = new MetricsHttpServer(gateway.metrics(), metricsPort);
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
        if (shardSpec.isBlank()) {
            System.out.println("chat target: " + chatHost + ":" + chatPort);
        } else {
            System.out.println("chat shards: " + shardSpec);
        }
        Thread.currentThread().join();
    }

    private static int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }
}
