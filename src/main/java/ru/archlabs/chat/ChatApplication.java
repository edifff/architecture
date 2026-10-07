package ru.archlabs.chat;

import ru.archlabs.chat.http.ChatHttpServer;
import ru.archlabs.chat.storage.PostgresChatStorage;

import java.util.concurrent.CountDownLatch;

/**
 * Точка входа процесса чата. Настраивается переменными окружения, поэтому один и
 * тот же образ годится и для локального запуска, и для запуска в контейнере.
 *
 * <ul>
 *   <li>{@code CHAT_DB_URL} — JDBC-адрес PostgreSQL (по умолчанию
 *       {@code jdbc:postgresql://localhost:5432/chat});
 *   <li>{@code CHAT_DB_USER}, {@code CHAT_DB_PASSWORD} — учётные данные (по умолчанию
 *       {@code chat}/{@code chat});
 *   <li>{@code CHAT_HTTP_PORT} — порт HTTP-сервера (по умолчанию {@code 8080}).
 * </ul>
 */
public final class ChatApplication {
    private ChatApplication() {
    }

    public static void main(String[] args) throws Exception {
        var config = Config.fromEnvironment();
        var server = start(config);
        System.out.println("Chat HTTP server is listening on " + server.baseUri());
        // Блокируем главный поток: сервер работает до получения SIGTERM.
        new CountDownLatch(1).await();
    }

    private static ChatHttpServer start(Config config) throws Exception {
        var storage =
                withRetries(
                        () ->
                                new PostgresChatStorage(
                                        config.jdbcUrl(), config.user(), config.password()));
        var server = new ChatHttpServer(new ChatServerImpl(storage), config.port());
        // Корректное завершение: Jetty останавливается, активные сессии закрываются,
        // а соединение с PostgreSQL освобождается, поэтому состояние не теряется.
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "chat-shutdown"));
        server.start();
        return server;
    }

    /**
     * PostgreSQL может стартовать позже приложения, поэтому подключение повторяется.
     * Flyway-миграции идемпотентны, так что повторный вызов безопасен.
     */
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
