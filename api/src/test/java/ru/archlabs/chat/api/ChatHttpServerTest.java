package ru.archlabs.chat.api;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ru.archlabs.chat.domain.ChatUser;
import ru.archlabs.chat.domain.Message;
import ru.archlabs.chat.http.ChatHttpServer;
import ru.archlabs.chat.service.ChatServerImpl;


final class ChatHttpServerTest {
    private CoreChatGateway gateway;
    private ChatHttpServer http;
    private HttpClient client;
    private URI base;

    @BeforeEach
    void setUp() throws Exception {
        gateway = new CoreChatGateway(new ChatServerImpl());
        http = new ChatHttpServer(ChatFacades.over(gateway), 0);
        http.start();
        base = http.baseUri();
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterEach
    void tearDown() {
        http.close();
        gateway.close();
    }

    @Test
    void connectReturnsCreatedAndExposesUserState() throws Exception {
        Assertions.assertEquals(201, post("/sessions/alice", "").statusCode());

        HttpResponse<String> response = get("/users/alice");
        Assertions.assertEquals(200, response.statusCode());
        ChatUser user = ChatJson.read(response.body(), ChatUser.class);
        Assertions.assertEquals("alice", user.name());
        Assertions.assertTrue(user.online());
    }

    @Test
    void duplicateConnectionReturnsConflict() throws Exception {
        Assertions.assertEquals(201, post("/sessions/alice", "").statusCode());

        HttpResponse<String> duplicate = post("/sessions/alice", "");

        Assertions.assertEquals(409, duplicate.statusCode());
        Assertions.assertEquals("User is already connected: alice", error(duplicate));
    }

    @Test
    void sendReturnsCreatedMessageAndHistory() throws Exception {
        post("/sessions/alice", "");

        HttpResponse<String> response = post("/messages/alice", "{\"text\":\"hello\"}");

        Assertions.assertEquals(201, response.statusCode());
        Message message = ChatJson.read(response.body(), Message.class);
        Assertions.assertEquals("alice", message.sender());
        Assertions.assertEquals("hello", message.text());

        HttpResponse<String> history = get("/messages/alice?limit=10");
        Assertions.assertEquals(200, history.statusCode());
        Assertions.assertTrue(history.body().contains("\"text\":\"hello\""));
    }

    @Test
    void notificationsCanBeDisabled() throws Exception {
        post("/sessions/alice", "");

        HttpResponse<String> response = put("/notifications/alice", "{\"enabled\":false}");

        Assertions.assertEquals(204, response.statusCode());
        Assertions.assertFalse(ChatJson.read(get("/users/alice").body(), ChatUser.class).notificationsEnabled());
    }

    @Test
    void disconnectMarksUserOffline() throws Exception {
        post("/sessions/alice", "");

        Assertions.assertEquals(204, delete("/sessions/alice").statusCode());
        Assertions.assertFalse(ChatJson.read(get("/users/alice").body(), ChatUser.class).online());
    }

    @Test
    void unknownUserAndRouteReturnNotFound() throws Exception {
        Assertions.assertEquals(404, get("/users/nobody").statusCode());
        Assertions.assertEquals(404, get("/nonsense").statusCode());
        Assertions.assertEquals(404, post("/messages/nobody", "{\"text\":\"hi\"}").statusCode());
    }

    @Test
    void malformedJsonAndBlankFieldsReturnBadRequest() throws Exception {
        post("/sessions/alice", "");

        Assertions.assertEquals(400, post("/messages/alice", "{not-json").statusCode());
        Assertions.assertEquals(400, post("/messages/alice", "{\"text\":\"   \"}").statusCode());
        Assertions.assertEquals(400, post("/sessions/%20", "").statusCode());
    }

    @Test
    void unsupportedMethodReturnsNotFound() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/sessions/alice"))
                .method("PATCH", HttpRequest.BodyPublishers.noBody())
                .build();

        Assertions.assertEquals(404, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void eventsStreamDeliversSentMessage() throws Exception {
        post("/sessions/alice", "");
        HttpRequest events =
                HttpRequest.newBuilder(base.resolve("/events/alice"))
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build();
        HttpResponse<InputStream> response = client.send(events, HttpResponse.BodyHandlers.ofInputStream());
        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertTrue(
                response.headers()
                        .firstValue("Content-Type")
                        .orElse("")
                        .startsWith("text/event-stream"));

        CompletableFuture<String> firstEvent =
                CompletableFuture.supplyAsync(
                        () -> {
                            try (BufferedReader reader =
                                    new BufferedReader(
                                            new InputStreamReader(
                                                    response.body(), StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    if (line.startsWith("data: ")) {
                                        return line;
                                    }
                                }
                                return null;
                            } catch (Exception exception) {
                                throw new IllegalStateException(exception);
                            }
                        });

        post("/sessions/bob", "");
        post("/messages/bob", "{\"text\":\"live\"}");

        String data = firstEvent.get(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(data);
        Assertions.assertTrue(data.contains("\"text\":\"live\""));
        response.body().close();
    }

    @Test
    void eventsForUnknownUserReturnNotFound() throws Exception {
        Assertions.assertEquals(404, get("/events/ghost").statusCode());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send("GET", path, null);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return send("POST", path, body);
    }

    private HttpResponse<String> put(String path, String body) throws Exception {
        return send("PUT", path, body);
    }

    private HttpResponse<String> delete(String path) throws Exception {
        return send("DELETE", path, null);
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.BodyPublisher publisher =
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        HttpRequest request =
                HttpRequest.newBuilder(base.resolve(path))
                        .header("Content-Type", "application/json")
                        .method(method, publisher)
                        .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String error(HttpResponse<String> response) {
        return ChatJson.read(response.body(), ErrorPayload.class).error();
    }
}
