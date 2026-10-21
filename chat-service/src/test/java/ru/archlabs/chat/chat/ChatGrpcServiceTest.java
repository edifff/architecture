package ru.archlabs.chat.chat;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.archlabs.chat.ChatServerImpl;
import ru.archlabs.chat.InMemoryChatStorage;
import ru.archlabs.proto.chat.ChatServiceGrpc;
import ru.archlabs.proto.chat.ConnectRequest;
import ru.archlabs.proto.chat.DisconnectRequest;
import ru.archlabs.proto.chat.HistoryRequest;
import ru.archlabs.proto.chat.SendRequest;
import ru.archlabs.proto.chat.SetNotificationsEnabledRequest;
import ru.archlabs.proto.chat.SubscribeRequest;
import ru.archlabs.proto.chat.UserRequest;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChatGrpcServiceTest {
    private ChatServerImpl chat;
    private io.grpc.Server server;
    private ManagedChannel channel;
    private ChatServiceGrpc.ChatServiceBlockingStub blocking;

    @BeforeEach
    void setUp() throws Exception {
        var name = InProcessServerBuilder.generateName();
        chat = new ChatServerImpl(new InMemoryChatStorage());
        server =
                InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(new ChatGrpcService(chat))
                        .build()
                        .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        blocking = ChatServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
        chat.close();
    }

    @Test
    void connectReturnsOnlineUser() {
        var response = blocking.connect(ConnectRequest.newBuilder().setName("ivan").build());

        assertTrue(response.getUser().getOnline());
        assertTrue(response.getUser().getNotificationsEnabled());
        assertTrue(response.getUser().hasLastConnected());
    }

    @Test
    void duplicateConnectFailsWithAlreadyExists() {
        blocking.connect(ConnectRequest.newBuilder().setName("ivan").build());

        var exception =
                assertThrows(
                        StatusRuntimeException.class,
                        () -> blocking.connect(ConnectRequest.newBuilder().setName("ivan").build()));
        assertEquals(Status.Code.ALREADY_EXISTS, exception.getStatus().getCode());
    }

    @Test
    void sendDeliversToSubscriber() throws Exception {
        blocking.connect(ConnectRequest.newBuilder().setName("ivan").build());
        var collector = new MessageCollector();
        ChatServiceGrpc.newStub(channel)
                .subscribe(
                        SubscribeRequest.newBuilder().setName("ivan").build(), collector);
        blocking.connect(ConnectRequest.newBuilder().setName("andray").build());

        var sent =
                blocking.send(
                        SendRequest.newBuilder().setName("andray").setText("hi").build());

        var delivered = collector.poll(5, TimeUnit.SECONDS);
        assertNotNull(delivered);
        assertEquals(sent.getId(), delivered.getId());
        assertEquals("andray", delivered.getSender());
        assertEquals("hi", delivered.getText());
    }

    @Test
    void historyRequiresNotificationsDisabled() {
        blocking.connect(ConnectRequest.newBuilder().setName("ivan").build());
        blocking.send(SendRequest.newBuilder().setName("ivan").setText("hello").build());

        var exception =
                assertThrows(
                        StatusRuntimeException.class,
                        () -> blocking.history(HistoryRequest.newBuilder().setName("ivan").build()));
        assertEquals(Status.Code.FAILED_PRECONDITION, exception.getStatus().getCode());

        blocking.setNotificationsEnabled(
                SetNotificationsEnabledRequest.newBuilder().setName("ivan").setEnabled(false).build());

        var history = blocking.history(HistoryRequest.newBuilder().setName("ivan").build());
        assertEquals(1, history.getMessagesCount());
        assertEquals("hello", history.getMessages(0).getText());
    }

    @Test
    void unknownUserFailsWithNotFound() {
        var exception =
                assertThrows(
                        StatusRuntimeException.class,
                        () -> blocking.user(UserRequest.newBuilder().setName("nobody").build()));
        assertEquals(Status.Code.NOT_FOUND, exception.getStatus().getCode());
    }

    @Test
    void disconnectMarksUserOffline() {
        blocking.connect(ConnectRequest.newBuilder().setName("ivan").build());

        blocking.disconnect(DisconnectRequest.newBuilder().setName("ivan").build());

        var user = blocking.user(UserRequest.newBuilder().setName("ivan").build());
        assertFalse(user.getOnline());
    }

    @Test
    void sendWithoutSessionFailsWithFailedPrecondition() {
        var exception =
                assertThrows(
                        StatusRuntimeException.class,
                        () ->
                                blocking.send(
                                        SendRequest.newBuilder()
                                                .setName("ghost")
                                                .setText("hi")
                                                .build()));

        assertEquals(Status.Code.FAILED_PRECONDITION, exception.getStatus().getCode());
    }

    @Test
    void sendBlankTextFailsWithInvalidArgument() {
        blocking.connect(ConnectRequest.newBuilder().setName("ivan").build());

        var exception =
                assertThrows(
                        StatusRuntimeException.class,
                        () ->
                                blocking.send(
                                        SendRequest.newBuilder()
                                                .setName("ivan")
                                                .setText("   ")
                                                .build()));

        assertEquals(Status.Code.INVALID_ARGUMENT, exception.getStatus().getCode());
    }

    @Test
    void connectBlankNameFailsWithInvalidArgument() {
        var exception =
                assertThrows(
                        StatusRuntimeException.class,
                        () -> blocking.connect(ConnectRequest.newBuilder().setName("   ").build()));

        assertEquals(Status.Code.INVALID_ARGUMENT, exception.getStatus().getCode());
    }

    @Test
    void disconnectUnknownFailsWithNotFound() {
        var exception =
                assertThrows(
                        StatusRuntimeException.class,
                        () ->
                                blocking.disconnect(
                                        DisconnectRequest.newBuilder().setName("ghost").build()));

        assertEquals(Status.Code.NOT_FOUND, exception.getStatus().getCode());
    }

    @Test
    void subscribeWithoutSessionFailsWithFailedPrecondition() {
        var exception =
                assertThrows(
                        StatusRuntimeException.class,
                        () ->
                                blocking.subscribe(
                                                SubscribeRequest.newBuilder()
                                                        .setName("ghost")
                                                        .build())
                                        .hasNext());

        assertEquals(Status.Code.FAILED_PRECONDITION, exception.getStatus().getCode());
    }

    @Test
    void storageFailureMapsToInternal() throws Exception {
        var name = InProcessServerBuilder.generateName();
        var failingChat = new ChatServerImpl(new FailingChatStorage());
        var failingServer =
                InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(new ChatGrpcService(failingChat))
                        .build()
                        .start();
        var failingChannel = InProcessChannelBuilder.forName(name).directExecutor().build();
        try {
            var stub = ChatServiceGrpc.newBlockingStub(failingChannel);
            var exception =
                    assertThrows(
                            StatusRuntimeException.class,
                            () ->
                                    stub.connect(
                                            ConnectRequest.newBuilder().setName("ivan").build()));

            assertEquals(Status.Code.INTERNAL, exception.getStatus().getCode());
        } finally {
            failingChannel.shutdownNow();
            failingServer.shutdownNow();
            failingChat.close();
        }
    }
}
