package ru.archlabs.chat.api;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.archlabs.proto.chat.MessagesResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FacadesTest {
    private FakeChatService fake;
    private Server server;
    private ManagedChannel channel;
    private ChatFacades facades;

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
        facades = ChatFacades.over(channel);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void sessionFacadeConnectsAndDisconnects() {
        var user = facades.sessions().connect("ivan");
        facades.sessions().disconnect("ivan");

        assertTrue(user.getOnline());
        assertEquals("ivan", fake.lastConnect.getName());
        assertEquals("ivan", fake.lastDisconnect.getName());
    }

    @Test
    void sessionFacadeRejectsBlankUsername() {
        assertThrows(IllegalArgumentException.class, () -> facades.sessions().connect("   "));
    }

    @Test
    void messageFacadeParsesBodyAndConvertsToProtobuf() {
        var message = facades.messages().send("ivan", "{\"text\":\"hello world\"}");

        assertEquals("ivan", fake.lastSend.getName());
        assertEquals("hello world", fake.lastSend.getText());
        assertEquals(7, message.getId());
    }

    @Test
    void messageFacadeRejectsMalformedJson() {
        assertThrows(
                IllegalArgumentException.class,
                () -> facades.messages().send("ivan", "{not-json"));
    }

    @Test
    void messageFacadeRejectsBlankText() {
        assertThrows(
                IllegalArgumentException.class,
                () -> facades.messages().send("ivan", "{\"text\":\"   \"}"));
    }

    @Test
    void messageFacadeReturnsHistory() {
        fake.history =
                MessagesResponse.newBuilder()
                        .addMessages(FakeChatService.message(1, "hi"))
                        .build();

        var history = facades.messages().history("ivan");

        assertEquals(1, history.size());
        assertEquals("hi", history.get(0).getText());
    }

    @Test
    void notificationFacadeParsesBody() {
        facades.notifications().setNotificationsEnabled("ivan", "{\"enabled\":false}");

        assertEquals("ivan", fake.lastNotifications.getName());
        assertFalse(fake.lastNotifications.getEnabled());
    }

    @Test
    void userFacadeReturnsState() {
        var user = facades.users().user("ivan");

        assertEquals("ivan", user.getName());
        assertTrue(user.getOnline());
    }

    @Test
    void facadesPropagateGrpcError() {
        fake.failure = Status.Code.UNAVAILABLE;

        var exception =
                assertThrows(
                        StatusRuntimeException.class, () -> facades.users().user("ivan"));

        assertEquals(Status.Code.UNAVAILABLE, exception.getStatus().getCode());
    }
}
