package ru.archlabs.chat.storage;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.archlabs.chat.metrics.ChatMetrics;
import ru.archlabs.proto.common.Empty;
import ru.archlabs.proto.storage.AppendMessageRequest;
import ru.archlabs.proto.storage.ConnectRequest;
import ru.archlabs.proto.storage.HistoryRequest;
import ru.archlabs.proto.storage.MarkDeliveredRequest;
import ru.archlabs.proto.storage.SetNotificationsRequest;
import ru.archlabs.proto.storage.StorageServiceGrpc;
import ru.archlabs.proto.storage.UserRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StorageGrpcServiceTest {
    private Server server;
    private ManagedChannel channel;
    private StorageServiceGrpc.StorageServiceBlockingStub storage;

    @BeforeEach
    void setUp() throws Exception {
        var name = InProcessServerBuilder.generateName();
        var service = new StorageGrpcService(new InMemoryChatStorage(), new ChatMetrics());
        server =
                InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(service)
                        .build()
                        .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        storage = StorageServiceGrpc.newBlockingStub(channel);
        storage.resetOnlineUsers(Empty.getDefaultInstance());
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void exposesStorageContractOverGrpc() {
        var connected =
                storage.connect(
                        ConnectRequest.newBuilder()
                                .setName("alice")
                                .setAtEpochMillis(1_000)
                                .build());
        assertTrue(connected.getOnline());

        var message =
                storage.appendMessage(
                        AppendMessageRequest.newBuilder()
                                .setSender("bob")
                                .setText("hello")
                                .addRecipients("alice")
                                .setAtEpochMillis(1_001)
                                .build());
        assertEquals("hello", message.getText());

        assertEquals(1, storage.history(HistoryRequest.newBuilder().setLimit(10).build()).getMessagesCount());
        var pending = storage.pendingNotifications(UserRequest.newBuilder().setName("alice").build());
        assertEquals(1, pending.getNotificationsCount());

        storage.markNotificationDelivered(
                MarkDeliveredRequest.newBuilder()
                        .setId(pending.getNotifications(0).getId())
                        .setAtEpochMillis(1_002)
                        .build());
        assertEquals(
                0,
                storage.pendingNotifications(UserRequest.newBuilder().setName("alice").build())
                        .getNotificationsCount());
    }

    @Test
    void persistsNotificationPreferenceThroughGrpc() {
        storage.connect(
                ConnectRequest.newBuilder().setName("alice").setAtEpochMillis(1_000).build());
        storage.setNotifications(
                SetNotificationsRequest.newBuilder().setName("alice").setEnabled(false).build());

        assertFalse(
                storage.findUser(UserRequest.newBuilder().setName("alice").build())
                        .getNotificationsEnabled());
    }
}
