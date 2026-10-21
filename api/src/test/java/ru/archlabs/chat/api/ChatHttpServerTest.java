package ru.archlabs.chat.api;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.archlabs.chat.http.ChatHttpServer;
import ru.archlabs.proto.chat.MessagesResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChatHttpServerTest {
    private FakeChatService fake;
    private Server grpcServer;
    private ManagedChannel channel;
    private ChatHttpServer httpServer;
    private HttpClient client;
    private URI base;

    @BeforeEach
    void setUp() throws Exception {
        var name = InProcessServerBuilder.generateName();
        fake = new FakeChatService();
        grpcServer =
                InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(fake)
                        .build()
                        .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        httpServer = new ChatHttpServer(new ChatFacade(channel), 0);
        httpServer.start();
        base = httpServer.baseUri();
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterEach
    void tearDown() {
        httpServer.close();
        channel.shutdownNow();
        grpcServer.shutdownNow();
    }

    @Test
    void connectReturnsCreatedAndExposesUserState() throws Exception {
        assertEquals(201, post("/sessions/ivan", "").statusCode());

        var response = get("/users/ivan");
        assertEquals(200, response.statusCode());
        var user = ChatJson.read(response.body(), UserPayload.class);
        assertEquals("ivan", user.name());
        assertTrue(user.online());
    }

    @Test
    void duplicateConnectionReturnsConflict() throws Exception {
        assertEquals(201, post("/sessions/ivan", "").statusCode());

        var duplicate = post("/sessions/ivan", "");
        assertEquals(409, duplicate.statusCode());
        assertEquals(
                "User is already connected: ivan",
                ChatJson.read(duplicate.body(), ErrorPayload.class).error());
    }

    @Test
    void sendReturnsCreatedMessage() throws Exception {
        post("/sessions/ivan", "");

        var response = post("/messages/ivan", ChatJson.write(new SendMessageRequest("hello")));

        assertEquals(201, response.statusCode());
        var message = ChatJson.read(response.body(), MessagePayload.class);
        assertEquals("ivan", message.sender());
        assertEquals("hello", message.text());
    }

    @Test
    void historyReturnsMessages() throws Exception {
        post("/sessions/ivan", "");
        fake.history =
                MessagesResponse.newBuilder()
                        .addMessages(FakeChatService.message(1, "hi"))
                        .build();

        var response = get("/messages/ivan");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"text\":\"hi\""));
    }

    @Test
    void notificationsReturnNoContent() throws Exception {
        post("/sessions/ivan", "");

        var response =
                put("/notifications/ivan", ChatJson.write(new NotificationsRequest(false)));

        assertEquals(204, response.statusCode());
        assertEquals("ivan", fake.lastNotifications.getName());
    }

    @Test
    void disconnectReturnsNoContent() throws Exception {
        post("/sessions/ivan", "");

        assertEquals(204, delete("/sessions/ivan").statusCode());
    }

    @Test
    void unknownUserReturnsNotFound() throws Exception {
        assertEquals(404, get("/users/nobody").statusCode());
    }

    @Test
    void blankUsernameReturnsBadRequest() throws Exception {
        var response = get("/users/");
        assertEquals(400, response.statusCode());
        assertEquals(
                "Username must not be blank",
                ChatJson.read(response.body(), ErrorPayload.class).error());
    }

    @Test
    void unknownRouteReturnsNotFound() throws Exception {
        assertEquals(404, get("/nonsense").statusCode());
    }

    @Test
    void malformedJsonReturnsBadRequest() throws Exception {
        post("/sessions/ivan", "");

        assertEquals(400, post("/messages/ivan", "{not-json").statusCode());
    }

    @Test
    void sendConflictReturnsConflict() throws Exception {
        fake.failure = io.grpc.Status.Code.FAILED_PRECONDITION;

        var response = post("/messages/ghost", ChatJson.write(new SendMessageRequest("hi")));

        assertEquals(409, response.statusCode());
    }

    @Test
    void blankTextReturnsBadRequest() throws Exception {
        post("/sessions/ivan", "");

        var response = post("/messages/ivan", ChatJson.write(new SendMessageRequest("   ")));

        assertEquals(400, response.statusCode());
    }

    @Test
    void historyConflictIsMappedFromGrpc() throws Exception {
        post("/sessions/ivan", "");
        fake.failure = io.grpc.Status.Code.FAILED_PRECONDITION;

        assertEquals(409, get("/messages/ivan").statusCode());
    }

    @Test
    void unavailableChatServiceReturnsServiceUnavailable() throws Exception {
        post("/sessions/ivan", "");
        fake.failure = io.grpc.Status.Code.UNAVAILABLE;

        var response = post("/messages/ivan", ChatJson.write(new SendMessageRequest("hi")));

        assertEquals(503, response.statusCode());
    }

    @Test
    void disconnectUnknownReturnsNotFound() throws Exception {
        fake.failure = io.grpc.Status.Code.NOT_FOUND;

        assertEquals(404, delete("/sessions/ghost").statusCode());
    }

    @Test
    void unknownMethodReturnsNotFound() throws Exception {
        var request =
                HttpRequest.newBuilder(base.resolve("/sessions/ivan"))
                        .method("PATCH", HttpRequest.BodyPublishers.noBody())
                        .build();

        assertEquals(
                404,
                client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void eventsUnknownUserReturnsNotFound() throws Exception {
        var request = HttpRequest.newBuilder(base.resolve("/events/ghost")).GET().build();

        assertEquals(
                404,
                client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void eventsStreamStartsWithConnectedComment() throws Exception {
        post("/sessions/ivan", "");
        var request =
                HttpRequest.newBuilder(base.resolve("/events/ivan"))
                        .header("Accept", ChatProtocol.CONTENT_TYPE_EVENT_STREAM)
                        .GET()
                        .build();
        var lines = client.send(request, HttpResponse.BodyHandlers.ofLines()).body();
        try {
            assertEquals(": connected", lines.findFirst().orElseThrow());
        } finally {
            lines.close();
        }
    }

    @Test
    void eventsStreamDeliversPushedMessage() throws Exception {
        post("/sessions/ivan", "");
        var request =
                HttpRequest.newBuilder(base.resolve("/events/ivan"))
                        .header("Accept", ChatProtocol.CONTENT_TYPE_EVENT_STREAM)
                        .GET()
                        .build();
        var lines = client.send(request, HttpResponse.BodyHandlers.ofLines()).body();
        try {
            var iterator = lines.iterator();
            assertEquals(": connected", iterator.next());
            fake.push(FakeChatService.message(42, "ping"));

            String data = null;
            while (iterator.hasNext()) {
                var line = iterator.next();
                if (line.startsWith("data:")) {
                    data = line;
                    break;
                }
            }
            assertNotNull(data);
            assertTrue(data.contains("\"text\":\"ping\""));
        } finally {
            lines.close();
        }
    }

    @Test
    void eventsStreamSurvivesAbruptClientDisconnect() throws Exception {
        post("/sessions/ivan", "");
        var request =
                HttpRequest.newBuilder(base.resolve("/events/ivan"))
                        .header("Accept", ChatProtocol.CONTENT_TYPE_EVENT_STREAM)
                        .GET()
                        .build();
        var lines = client.send(request, HttpResponse.BodyHandlers.ofLines()).body();
        assertEquals(": connected", lines.findFirst().orElseThrow());
        lines.close();

        assertEquals(200, get("/users/ivan").statusCode());
    }

    @Test
    void notificationsConflictReturnsConflict() throws Exception {
        fake.failure = io.grpc.Status.Code.FAILED_PRECONDITION;

        var response =
                put("/notifications/ghost", ChatJson.write(new NotificationsRequest(false)));

        assertEquals(409, response.statusCode());
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
}
