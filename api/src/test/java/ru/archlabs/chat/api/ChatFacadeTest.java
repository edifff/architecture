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
    void parsesSendBodyAndConvertsToProtobuf() {
        var message = facade.send("ivan", "{\"text\":\"hello world\"}");

        assertEquals("ivan", fake.lastSend.getName());
        assertEquals("hello world", fake.lastSend.getText());
        assertEquals(7, message.getId());
    }

    @Test
    void parsesNotificationsBody() {
        facade.setNotificationsEnabled("ivan", "{\"enabled\":false}");

        assertEquals("ivan", fake.lastNotifications.getName());
        assertFalse(fake.lastNotifications.getEnabled());
    }

    @Test
    void convertsConnectToProtobuf() {
        var user = facade.connect("ivan");

        assertEquals("ivan", fake.lastConnect.getName());
        assertTrue(user.getOnline());
    }

    @Test
    void convertsDisconnectToProtobuf() {
        facade.disconnect("ivan");

        assertEquals("ivan", fake.lastDisconnect.getName());
    }

    @Test
    void returnsHistoryFromProtobufResponse() {
        fake.history =
                MessagesResponse.newBuilder()
                        .addMessages(FakeChatService.message(1, "hi"))
                        .build();

        var history = facade.history("ivan");

        assertEquals(1, history.size());
        assertEquals("hi", history.get(0).getText());
    }

    @Test
    void returnsUserState() {
        var user = facade.user("ivan");

        assertEquals("ivan", user.getName());
        assertTrue(user.getOnline());
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(IllegalArgumentException.class, () -> facade.send("ivan", "{not-json"));
    }

    @Test
    void rejectsBlankText() {
        assertThrows(
                IllegalArgumentException.class,
                () -> facade.send("ivan", "{\"text\":\"   \"}"));
    }

    @Test
    void rejectsBlankUsername() {
        assertThrows(IllegalArgumentException.class, () -> facade.connect("   "));
    }

    @Test
    void propagatesGrpcError() {
        fake.failure = Status.Code.UNAVAILABLE;

        var exception =
                assertThrows(StatusRuntimeException.class, () -> facade.user("ivan"));

        assertEquals(Status.Code.UNAVAILABLE, exception.getStatus().getCode());
    }
}
