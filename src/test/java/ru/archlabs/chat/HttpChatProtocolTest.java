package ru.archlabs.chat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.archlabs.chat.http.ChatHttpServer;
import ru.archlabs.chat.http.ChatJson;
import ru.archlabs.chat.http.ChatProtocol;
import ru.archlabs.chat.storage.PostgresChatStorage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты HTTP-протокола: коды ответов, структура JSON и доставка оповещений
 * через Server-Sent Events. Запросы выполняются напрямую, без клиентской обёртки.
 */
@Testcontainers
final class HttpChatProtocolTest {
    static {
        System.setProperty("api.version", "1.40");
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private ChatHttpServer server;
    private HttpClient client;
    private URI base;

    @BeforeEach
    void setUp() throws Exception {
        var storage =
                new PostgresChatStorage(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        cleanDatabase();
        server = new ChatHttpServer(new ChatServerImpl(storage), 0);
        server.start();
        base = server.baseUri();
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void connectReturnsCreatedAndExposesUserState() throws Exception {
        assertEquals(201, post("/sessions/ivan", "").statusCode());

        var response = get("/users/ivan");
        assertEquals(200, response.statusCode());
        var user = ChatJson.read(response.body(), ChatProtocol.UserPayload.class);
        assertTrue(user.online());
        assertTrue(user.notificationsEnabled());
        assertNotNull(user.lastConnected());
    }

    @Test
    void duplicateConnectionReturnsConflict() throws Exception {
        assertEquals(201, post("/sessions/ivan", "").statusCode());

        var duplicate = post("/sessions/ivan", "");
        assertEquals(409, duplicate.statusCode());
        assertNotNull(ChatJson.read(duplicate.body(), ChatProtocol.ErrorPayload.class).error());
    }

    @Test
    void sendReturnsCreatedMessage() throws Exception {
        post("/sessions/ivan", "");

        var response =
                post(
                        "/messages/ivan",
                        ChatJson.write(new ChatProtocol.SendMessageRequest("hello")));
        assertEquals(201, response.statusCode());
        var message = ChatJson.read(response.body(), ChatProtocol.MessagePayload.class);
        assertEquals("ivan", message.sender());
        assertEquals("hello", message.text());
        assertTrue(message.id() >= 1);
        assertNotNull(Instant.parse(message.sentAt()));
    }

    @Test
    void historyIsForbiddenUntilNotificationsDisabled() throws Exception {
        post("/sessions/ivan", "");
        assertEquals(
                201,
                post(
                                "/messages/ivan",
                                ChatJson.write(new ChatProtocol.SendMessageRequest("hello")))
                        .statusCode());

        assertEquals(409, get("/messages/ivan").statusCode());

        assertEquals(
                204,
                put(
                                "/notifications/ivan",
                                ChatJson.write(new ChatProtocol.NotificationsRequest(false)))
                        .statusCode());

        var response = get("/messages/ivan");
        assertEquals(200, response.statusCode());
        var messages = ChatJson.readList(response.body(), ChatProtocol.MessagePayload.class);
        assertEquals(
                List.of("hello"),
                messages.stream().map(ChatProtocol.MessagePayload::text).toList());
    }

    @Test
    void disconnectUpdatesUserState() throws Exception {
        post("/sessions/ivan", "");

        assertEquals(204, delete("/sessions/ivan").statusCode());

        var user = ChatJson.read(get("/users/ivan").body(), ChatProtocol.UserPayload.class);
        assertFalse(user.online());
    }

    @Test
    void unknownUserReturnsNotFound() throws Exception {
        assertEquals(404, get("/users/nobody").statusCode());
    }

    @Test
    void eventsStreamDeliversMessages() throws Exception {
        post("/sessions/ivan", "");
        post("/sessions/andray", "");

        var received = new LinkedBlockingQueue<ChatProtocol.MessagePayload>();
        var stream = openEvents("ivan", received);

        try {
            var response =
                    post(
                            "/messages/andray",
                            ChatJson.write(new ChatProtocol.SendMessageRequest("hi")));
            assertEquals(201, response.statusCode());

            var event = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(event);
            assertEquals("andray", event.sender());
            assertEquals("hi", event.text());
        } finally {
            stream.close();
        }
    }

    @Test
    void metricsEndpointExposesProcessedRequestsAndNotifications() throws Exception {
        post("/sessions/ivan", "");
        post("/sessions/andray", "");
        post(
                "/messages/andray",
                ChatJson.write(new ChatProtocol.SendMessageRequest("hi")));

        var response = get("/metrics");
        assertEquals(200, response.statusCode());
        var body = response.body();
        assertTrue(body.contains("chat_requests_total"));
        assertTrue(body.contains("chat_requests_total{operation=\"connect\"} 2.0"));
        assertTrue(body.contains("chat_requests_total{operation=\"send\"} 1.0"));
        assertTrue(body.contains("chat_notifications_total"));
        assertTrue(body.contains("chat_notifications_total{channel=\"live\"} 2.0"));
    }

    private Stream<String> openEvents(
            String username, LinkedBlockingQueue<ChatProtocol.MessagePayload> received) {
        var request =
                HttpRequest.newBuilder(base.resolve("/events/" + username))
                        .header("Accept", ChatProtocol.CONTENT_TYPE_EVENT_STREAM)
                        .GET()
                        .build();
        var stream = sendStream(request);
        var reader =
                new Thread(
                        () ->
                                stream.forEach(
                                        line -> {
                                            if (line.startsWith(ChatProtocol.DATA_PREFIX)) {
                                                received.add(
                                                        ChatJson.read(
                                                                line.substring(
                                                                                ChatProtocol
                                                                                        .DATA_PREFIX
                                                                                        .length())
                                                                        .trim(),
                                                                ChatProtocol.MessagePayload.class));
                                            }
                                        }));
        reader.setDaemon(true);
        reader.start();
        return stream;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(base.resolve(path)).GET().build());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return send(
                HttpRequest.newBuilder(base.resolve(path))
                        .header("Content-Type", ChatProtocol.CONTENT_TYPE_JSON)
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        body, StandardCharsets.UTF_8))
                        .build());
    }

    private HttpResponse<String> put(String path, String body) throws Exception {
        return send(
                HttpRequest.newBuilder(base.resolve(path))
                        .header("Content-Type", ChatProtocol.CONTENT_TYPE_JSON)
                        .PUT(
                                HttpRequest.BodyPublishers.ofString(
                                        body, StandardCharsets.UTF_8))
                        .build());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        return send(HttpRequest.newBuilder(base.resolve(path)).DELETE().build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private Stream<String> sendStream(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofLines()).body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while opening event stream", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot open event stream", exception);
        }
    }

    private static void cleanDatabase() throws SQLException {
        try (var connection =
                     DriverManager.getConnection(
                             POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE messages, users RESTART IDENTITY CASCADE");
        }
    }
}
