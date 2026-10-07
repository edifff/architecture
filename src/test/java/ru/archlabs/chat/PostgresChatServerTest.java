package ru.archlabs.chat;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.archlabs.chat.storage.PostgresChatStorage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Прямое подключение к {@link ChatServerImpl} с хранилищем PostgreSQL. */
@Testcontainers
final class PostgresChatServerTest extends ChatServerContractTest {
    static {
        System.setProperty("api.version", "1.40");
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private ChatServer server;

    @Override
    ChatServer createServer() {
        server = new ChatServerImpl(storage());
        return server;
    }

    @Override
    void resetDatabase() throws Exception {
        try (var connection = connect();
             var statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE messages, users RESTART IDENTITY CASCADE");
        }
    }

    @Override
    void closeEnvironment() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void restoresStateAfterRestart() {
        var ivan = server.connect("ivan", ignored -> {
        });
        ivan.setNotificationsEnabled(false);
        server.connect("andray", ignored -> {
        }).send("persisted");
        var expectedHistory = ivan.history();
        var beforeClose = Instant.now().minusSeconds(1);
        server.close();
        var afterClose = Instant.now().plusSeconds(1);

        try (var restarted = new ChatServerImpl(storage())) {
            var state = restarted.user("ivan");
            assertFalse(state.online());
            assertFalse(state.notificationsEnabled());
            assertFalse(state.lastConnected().isBefore(beforeClose));
            assertFalse(state.lastConnected().isAfter(afterClose));
            try (var reopened = restarted.connect("ivan", ignored -> {
            })) {
                assertEquals(expectedHistory, reopened.history());
            }
        }
    }

    @Test
    void undeliveredNotificationsSurviveAbnormalShutdown() throws Exception {
        var crashStorage = storage();
        var crashed = new ChatServerImpl(crashStorage);
        crashed.connect("ava", ignored -> {
        }).close();
        crashed.connect("bob", ignored -> {
        }).send("durable");
        // Аварийное завершение: graceful ChatServerImpl.close() не вызывается,
        // соединение с базой просто обрывается.
        crashStorage.close();

        try (var restarted = new ChatServerImpl(storage())) {
            var inbox = new Inbox();
            restarted.connect("ava", inbox::accept);
            var message = inbox.await();
            assertNotNull(message);
            assertEquals("durable", message.text());
        }
    }

    private static PostgresChatStorage storage() {
        return new PostgresChatStorage(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
