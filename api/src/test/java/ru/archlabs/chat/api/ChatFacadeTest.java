package ru.archlabs.chat.api;

import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.archlabs.proto.chat.ChatServiceGrpc;
import ru.archlabs.proto.chat.ConnectRequest;
import ru.archlabs.proto.chat.ConnectResponse;
import ru.archlabs.proto.chat.DisconnectRequest;
import ru.archlabs.proto.chat.HistoryRequest;
import ru.archlabs.proto.chat.MessagesResponse;
import ru.archlabs.proto.chat.SendRequest;
import ru.archlabs.proto.chat.SetNotificationsEnabledRequest;
import ru.archlabs.proto.chat.UserRequest;
import ru.archlabs.proto.common.Empty;
import ru.archlabs.proto.common.Message;
import ru.archlabs.proto.common.UserState;

import java.time.Instant;

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
        fake.history = MessagesResponse.newBuilder().addMessages(message(1, "hi")).build();

        var result = facade.handle("HISTORY ivan");

        assertEquals("#1 ivan: hi", result);
    }

    @Test
    void convertsDisconnectToProtobufRequest() {
        facade.handle("DISCONNECT ivan");

        assertEquals("ivan", fake.lastDisconnect.getName());
    }

    private static Message message(long id, String text) {
        return Message.newBuilder()
                .setId(id)
                .setSender("ivan")
                .setText(text)
                .setSentAt(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()).build())
                .build();
    }

    private static final class FakeChatService extends ChatServiceGrpc.ChatServiceImplBase {
        private final UserState user =
                UserState.newBuilder()
                        .setName("ivan")
                        .setOnline(true)
                        .setNotificationsEnabled(true)
                        .setLastConnected(Timestamp.newBuilder().setSeconds(1).build())
                        .build();
        private ConnectRequest lastConnect;
        private SendRequest lastSend;
        private DisconnectRequest lastDisconnect;
        private SetNotificationsEnabledRequest lastNotifications;
        private MessagesResponse history = MessagesResponse.getDefaultInstance();

        @Override
        public void connect(ConnectRequest request, StreamObserver<ConnectResponse> observer) {
            lastConnect = request;
            observer.onNext(ConnectResponse.newBuilder().setUser(user).build());
            observer.onCompleted();
        }

        @Override
        public void disconnect(DisconnectRequest request, StreamObserver<Empty> observer) {
            lastDisconnect = request;
            observer.onNext(Empty.getDefaultInstance());
            observer.onCompleted();
        }

        @Override
        public void send(SendRequest request, StreamObserver<Message> observer) {
            lastSend = request;
            observer.onNext(message(7, request.getText()));
            observer.onCompleted();
        }

        @Override
        public void history(HistoryRequest request, StreamObserver<MessagesResponse> observer) {
            observer.onNext(history);
            observer.onCompleted();
        }

        @Override
        public void setNotificationsEnabled(
                SetNotificationsEnabledRequest request, StreamObserver<Empty> observer) {
            lastNotifications = request;
            observer.onNext(Empty.getDefaultInstance());
            observer.onCompleted();
        }

        @Override
        public void user(UserRequest request, StreamObserver<UserState> observer) {
            observer.onNext(user);
            observer.onCompleted();
        }
    }
}
