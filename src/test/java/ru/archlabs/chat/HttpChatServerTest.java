package ru.archlabs.chat;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.archlabs.chat.http.ChatHttpServer;
import ru.archlabs.chat.http.HttpChatClient;
import ru.archlabs.chat.storage.PostgresChatStorage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Тот же контракт чата, но клиент и сервер общаются по HTTP. */
@Testcontainers
final class HttpChatServerTest extends ChatServerContractTest {
    static {
        System.setProperty("api.version", "1.40");
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private ChatHttpServer httpServer;
    private HttpChatClient client;

    @Override
    ChatServer createServer() throws Exception {
        httpServer = new ChatHttpServer(new ChatServerImpl(storage()), 0);
        httpServer.start();
        client = new HttpChatClient(httpServer.baseUri());
        return client;
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
        if (client != null) {
            client.close();
        }
        if (httpServer != null) {
            httpServer.close();
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
