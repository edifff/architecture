package ru.archlabs.chat.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.archlabs.chat.domain.ChatMessage;
import ru.archlabs.chat.domain.UserState;
import ru.archlabs.chat.http.ChatHttpServer;
import ru.archlabs.chat.service.ChatServerImpl;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(201, post("/sessions/alice", "").statusCode());

        var response = get("/users/alice");
        assertEquals(200, response.statusCode());
        var user = ChatJson.read(response.body(), UserState.class);
        assertEquals("alice", user.name());
        assertTrue(user.online());
    }

    @Test
    void duplicateConnectionReturnsConflict() throws Exception {
        assertEquals(201, post("/sessions/alice", "").statusCode());

        var duplicate = post("/sessions/alice", "");

        assertEquals(409, duplicate.statusCode());
        assertEquals("User is already connected: alice", error(duplicate));
    }

    @Test
    void sendReturnsCreatedMessageAndHistory() throws Exception {
        post("/sessions/alice", "");

        var response = post("/messages/alice", "{\"text\":\"hello\"}");

        assertEquals(201, response.statusCode());
        var message = ChatJson.read(response.body(), ChatMessage.class);
        assertEquals("alice", message.sender());
        assertEquals("hello", message.text());

        var history = get("/messages/alice?limit=10");
        assertEquals(200, history.statusCode());
        assertTrue(history.body().contains("\"text\":\"hello\""));
    }

    @Test
    void notificationsCanBeDisabled() throws Exception {
        post("/sessions/alice", "");

        var response = put("/notifications/alice", "{\"enabled\":false}");

        assertEquals(204, response.statusCode());
        assertFalse(ChatJson.read(get("/users/alice").body(), UserState.class).notificationsEnabled());
    }

    @Test
    void disconnectMarksUserOffline() throws Exception {
        post("/sessions/alice", "");

        assertEquals(204, delete("/sessions/alice").statusCode());
        assertFalse(ChatJson.read(get("/users/alice").body(), UserState.class).online());
    }

    @Test
    void unknownUserAndRouteReturnNotFound() throws Exception {
        assertEquals(404, get("/users/nobody").statusCode());
        assertEquals(404, get("/nonsense").statusCode());
        assertEquals(404, post("/messages/nobody", "{\"text\":\"hi\"}").statusCode());
    }

    @Test
    void malformedJsonAndBlankFieldsReturnBadRequest() throws Exception {
        post("/sessions/alice", "");

        assertEquals(400, post("/messages/alice", "{not-json").statusCode());
        assertEquals(400, post("/messages/alice", "{\"text\":\"   \"}").statusCode());
        assertEquals(400, post("/sessions/%20", "").statusCode());
    }

    @Test
    void unsupportedMethodReturnsNotFound() throws Exception {
        var request = HttpRequest.newBuilder(base.resolve("/sessions/alice"))
                .method("PATCH", HttpRequest.BodyPublishers.noBody())
                .build();

        assertEquals(404, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void eventsStreamDeliversSentMessage() throws Exception {
        post("/sessions/alice", "");
        var events =
                HttpRequest.newBuilder(base.resolve("/events/alice"))
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build();
        var response = client.send(events, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        assertTrue(
                response.headers()
                        .firstValue("Content-Type")
                        .orElse("")
                        .startsWith("text/event-stream"));

        var firstEvent =
                CompletableFuture.supplyAsync(
                        () -> {
                            try (var reader =
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

        var data = firstEvent.get(5, TimeUnit.SECONDS);
        assertNotNull(data);
        assertTrue(data.contains("\"text\":\"live\""));
        response.body().close();
    }

    @Test
    void eventsForUnknownUserReturnNotFound() throws Exception {
        assertEquals(404, get("/events/ghost").statusCode());
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
        var publisher =
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        var request =
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
