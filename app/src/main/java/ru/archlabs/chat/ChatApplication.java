package ru.archlabs.chat;

import ru.archlabs.chat.http.ChatHttpServer;
import ru.archlabs.chat.storage.PostgresChatStorage;

import java.util.concurrent.CountDownLatch;


public final class ChatApplication {
    private ChatApplication() {
    }

    public static void main(String[] args) throws Exception {
        var config = Config.fromEnvironment();
        var server = start(config);
        System.out.println("Chat HTTP server is listening on " + server.baseUri());
        new CountDownLatch(1).await();
    }

    private static ChatHttpServer start(Config config) throws Exception {
        var storage =
                withRetries(
                        () ->
                                new PostgresChatStorage(
                                        config.jdbcUrl(), config.user(), config.password()));
        var server = new ChatHttpServer(new ChatServerImpl(storage), config.port());
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "chat-shutdown"));
        server.start();
        return server;
    }

    private static PostgresChatStorage withRetries(StorageFactory factory)
            throws InterruptedException {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= 30; attempt++) {
            try {
                return factory.create();
            } catch (RuntimeException exception) {
                lastFailure = exception;
                System.err.println(
                        "PostgreSQL is not ready (attempt "
                                + attempt
                                + "/30): "
                                + exception.getMessage());
                Thread.sleep(2_000L);
            }
        }
        throw lastFailure;
    }

    private interface StorageFactory {
        PostgresChatStorage create();
    }

    private record Config(String jdbcUrl, String user, String password, int port) {
        static Config fromEnvironment() {
            return new Config(
                    value("CHAT_DB_URL", "jdbc:postgresql://localhost:5432/chat"),
                    value("CHAT_DB_USER", "chat"),
                    value("CHAT_DB_PASSWORD", "chat"),
                    Integer.parseInt(value("CHAT_HTTP_PORT", "8080")));
        }

        private static String value(String name, String fallback) {
            var value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value;
        }
    }
}
