package ru.archlabs.chat.app;

import ru.archlabs.chat.api.ChatFacades;
import ru.archlabs.chat.api.CoreChatGateway;
import ru.archlabs.chat.http.ChatHttpServer;
import ru.archlabs.chat.metrics.MetricsHttpServer;
import ru.archlabs.chat.service.ChatServerImpl;
import ru.archlabs.chat.storage.ChatStorage;
import ru.archlabs.chat.storage.InMemoryChatStorage;
import ru.archlabs.chat.storage.JdbcChatStorage;

/**
 * Lab 3 runner: Netty HTTP/SSE over the in-process domain and H2 storage.
 */
public final class LocalChatApplication {
    private static final String STORAGE_ENV = "ARCHLABS_STORAGE";
    private static final String DEFAULT_STORAGE = "h2";

    private LocalChatApplication() {
    }

    public static void main(String[] args) throws Exception {
        int httpPort = intEnv("ARCHLABS_HTTP_PORT", 8080);
        int metricsPort = intEnv("ARCHLABS_METRICS_PORT", 9100);
        ChatStorage storage = storage();
        ChatServerImpl server = new ChatServerImpl(storage);
        CoreChatGateway gateway = new CoreChatGateway(server);
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

        System.out.println("HTTP:      " + http.baseUri());
        System.out.println("metrics:   http://localhost:" + metrics.port() + "/metrics");
        System.out.println("storage:   " + System.getenv().getOrDefault(STORAGE_ENV, DEFAULT_STORAGE));
        Thread.currentThread().join();
    }

    private static ChatStorage storage() {
        if ("memory".equalsIgnoreCase(System.getenv().getOrDefault(STORAGE_ENV, DEFAULT_STORAGE))) {
            return new InMemoryChatStorage();
        }
        String url =
                System.getenv()
                        .getOrDefault(
                                "ARCHLABS_DB_URL",
                                "jdbc:h2:file:./data/chat;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0");
        return new JdbcChatStorage(url);
    }

    private static int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }
}
