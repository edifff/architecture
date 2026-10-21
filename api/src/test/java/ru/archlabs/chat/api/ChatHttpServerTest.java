package ru.archlabs.chat.api;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.archlabs.chat.http.ChatHttpServer;
import ru.archlabs.chat.http.ChatJson;
import ru.archlabs.chat.http.ChatProtocol;
import ru.archlabs.chat.http.ErrorPayload;
import ru.archlabs.chat.http.MessagePayload;
import ru.archlabs.chat.http.NotificationsRequest;
import ru.archlabs.chat.http.SendMessageRequest;
import ru.archlabs.chat.http.UserPayload;
import ru.archlabs.proto.chat.MessagesResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Слой API отдельно: Netty HTTP + Facade поверх in-process gRPC-сервиса (без БД). */
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
