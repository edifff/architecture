package ru.archlabs.chat.chat;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.archlabs.chat.service.ChatServerImpl;
import ru.archlabs.proto.chat.ChatServiceGrpc;
import ru.archlabs.proto.chat.ConnectRequest;
import ru.archlabs.proto.chat.HistoryRequest;
import ru.archlabs.proto.chat.SendMessageRequest;
import ru.archlabs.proto.chat.SetNotificationsRequest;
import ru.archlabs.proto.chat.UserRequest;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChatGrpcServiceTest {
    private ChatServerImpl domain;
    private Server server;
    private ManagedChannel channel;
    private ChatServiceGrpc.ChatServiceBlockingStub chat;

    @BeforeEach
    void setUp() throws Exception {
        var name = InProcessServerBuilder.generateName();
        domain = new ChatServerImpl();
        server =
                InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(new ChatGrpcService(domain))
                        .build()
                        .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        chat = ChatServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
        domain.close();
    }

    @Test
    void exposesDomainContractOverGrpc() {
        assertTrue(connect("alice").getOnline());

        var message =
                chat.send(
                        SendMessageRequest.newBuilder()
                                .setName("alice")
                                .setText("hello")
                                .build());
        assertEquals("hello", message.getText());
        assertEquals(
                1,
                chat.history(
                                HistoryRequest.newBuilder()
                                        .setName("alice")
                                        .setLimit(10)
                                        .build())
                        .getMessagesCount());

        chat.setNotifications(
                SetNotificationsRequest.newBuilder().setName("alice").setEnabled(false).build());
        assertFalse(
                chat.getUser(UserRequest.newBuilder().setName("alice").build())
                        .getNotificationsEnabled());
    }

    @Test
    void streamsLiveMessages() throws Exception {
        connect("alice");
        connect("bob");
        var iterator = chat.subscribe(UserRequest.newBuilder().setName("alice").build());
        var next = CompletableFuture.supplyAsync(iterator::next);

        chat.send(SendMessageRequest.newBuilder().setName("bob").setText("live").build());

        var message = next.get(5, TimeUnit.SECONDS);
        assertEquals("bob", message.getSender());
        assertEquals("live", message.getText());
    }

    @Test
    void duplicateConnectionMapsToAlreadyExists() {
        connect("alice");

        var error =
                assertThrows(
                        StatusRuntimeException.class,
                        () -> connect("alice"));
        assertEquals(Status.Code.ALREADY_EXISTS, error.getStatus().getCode());
    }

    @Test
    void disconnectMarksUserOffline() {
        connect("alice");
        chat.disconnect(UserRequest.newBuilder().setName("alice").build());

        assertFalse(
                chat.getUser(UserRequest.newBuilder().setName("alice").build()).getOnline());
    }

    private ru.archlabs.proto.common.UserState connect(String user) {
        return chat.connect(ConnectRequest.newBuilder().setName(user).build());
    }
}
