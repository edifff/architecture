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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Facade отдельно: проверяем, что разобранное сообщение превращается в правильный
 * protobuf-запрос (а не в вызов домена напрямую), и что проверки срабатывают до RPC.
 */
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
    void convertsConnectToProtobufRequest() {
        var result = facade.handle("CONNECT ivan");

        assertEquals("ivan", fake.lastConnect.getName());
        assertEquals("connected ivan online=true", result);
    }

    @Test
    void convertsSendToProtobufRequest() {
        var result = facade.handle("SEND ivan hello world");

        assertEquals("ivan", fake.lastSend.getName());
        assertEquals("hello world", fake.lastSend.getText());
        assertEquals("sent #7 ivan: hello world", result);
    }

    @Test
    void convertsNotificationsFlagToProtobuf() {
        facade.handle("SET_NOTIFICATIONS ivan false");

        assertFalse(fake.lastNotifications.getEnabled());
        assertEquals("ivan", fake.lastNotifications.getName());
    }

    @Test
    void rejectsBlankTextBeforeCallingChatService() {
        assertThrows(IllegalArgumentException.class, () -> facade.handle("SEND ivan"));
        assertNull(fake.lastSend);
    }

    @Test
    void rejectsBlankUsername() {
        assertThrows(IllegalArgumentException.class, () -> facade.connect(" "));
    }

    @Test
    void rendersHistoryFromProtobufResponse() {
        fake.history =
                MessagesResponse.newBuilder()
                        .addMessages(FakeChatService.message(1, "hi"))
                        .build();

        var result = facade.handle("HISTORY ivan");

        assertEquals("#1 ivan: hi", result);
    }

    @Test
    void convertsDisconnectToProtobufRequest() {
        facade.handle("DISCONNECT ivan");

        assertEquals("ivan", fake.lastDisconnect.getName());
    }
}
