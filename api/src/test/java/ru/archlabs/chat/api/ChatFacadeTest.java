package ru.archlabs.chat.api;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.archlabs.proto.chat.MessagesResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChatFacadeTest {
    private FakeChatService fake;
    private Server server;
    private ManagedChannel channel;
    private ChatFacade facade;

    @BeforeEach
    void setUp() throws Exception {
        var name = InProcessServerBuilder.generateName();
        fake = new FakeChatService();
        server =
                InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(fake)
                        .build()
                        .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        facade = new ChatFacade(channel);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void parsesConnectAndOpensSession() {
        var response = facade.handle(new FacadeRequest("POST", "/sessions/ivan", ""));

        assertEquals(201, response.status());
        assertEquals(FacadeAction.OPEN, response.action());
        assertEquals("ivan", response.username());
        assertEquals("ivan", fake.lastConnect.getName());
    }

    @Test
    void parsesSendJsonBodyAndConvertsToProtobuf() {
        var response =
                facade.handle(
                        new FacadeRequest(
                                "POST", "/messages/ivan", "{\"text\":\"hello world\"}"));

        assertEquals(201, response.status());
        assertEquals("ivan", fake.lastSend.getName());
        assertEquals("hello world", fake.lastSend.getText());
        assertTrue(response.body().contains("hello world"));
    }

    @Test
    void parsesNotificationsFlag() {
        var response =
                facade.handle(
                        new FacadeRequest(
                                "PUT", "/notifications/ivan", "{\"enabled\":false}"));

        assertEquals(204, response.status());
        assertEquals("ivan", fake.lastNotifications.getName());
        assertFalse(fake.lastNotifications.getEnabled());
    }

    @Test
    void parsesDisconnect() {
        var response = facade.handle(new FacadeRequest("DELETE", "/sessions/ivan", ""));

        assertEquals(204, response.status());
        assertEquals(FacadeAction.CLOSE, response.action());
        assertEquals("ivan", fake.lastDisconnect.getName());
    }

    @Test
    void returnsStreamActionForEvents() {
        var response = facade.handle(new FacadeRequest("GET", "/events/ivan", ""));

        assertEquals(200, response.status());
        assertEquals(FacadeAction.STREAM, response.action());
        assertEquals("ivan", response.username());
    }

    @Test
    void rendersHistoryFromProtobufResponse() {
        fake.history =
                MessagesResponse.newBuilder()
                        .addMessages(FakeChatService.message(1, "hi"))
                        .build();

        var response = facade.handle(new FacadeRequest("GET", "/messages/ivan", ""));

        assertEquals(200, response.status());
        assertTrue(response.body().contains("\"text\":\"hi\""));
    }

    @Test
    void rejectsMalformedJson() {
        var response =
                facade.handle(new FacadeRequest("POST", "/messages/ivan", "{not-json"));

        assertEquals(400, response.status());
    }

    @Test
    void rejectsBlankText() {
        var response =
                facade.handle(
                        new FacadeRequest("POST", "/messages/ivan", "{\"text\":\"   \"}"));

        assertEquals(400, response.status());
    }

    @Test
    void rejectsBlankUsername() {
        var response = facade.handle(new FacadeRequest("GET", "/users/", ""));

        assertEquals(400, response.status());
    }

    @Test
    void unknownRouteReturnsNotFound() {
        var response = facade.handle(new FacadeRequest("GET", "/nonsense", ""));

        assertEquals(404, response.status());
    }

    @Test
    void unknownMethodReturnsNotFound() {
        var response = facade.handle(new FacadeRequest("PATCH", "/sessions/ivan", ""));

        assertEquals(404, response.status());
    }

    @Test
    void unknownUserMapsToNotFound() {
        var response = facade.handle(new FacadeRequest("GET", "/users/nobody", ""));

        assertEquals(404, response.status());
    }

    @Test
    void conflictMapsTo409() {
        fake.failure = io.grpc.Status.Code.FAILED_PRECONDITION;

        var response = facade.handle(new FacadeRequest("GET", "/messages/ivan", ""));

        assertEquals(409, response.status());
    }

    @Test
    void unavailableMapsTo503() {
        fake.failure = io.grpc.Status.Code.UNAVAILABLE;

        var response = facade.handle(new FacadeRequest("GET", "/messages/ivan", ""));

        assertEquals(503, response.status());
    }
}
