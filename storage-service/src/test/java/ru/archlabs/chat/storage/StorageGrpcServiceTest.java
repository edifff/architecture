package ru.archlabs.chat.storage;

import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.archlabs.proto.common.Empty;
import ru.archlabs.proto.storage.AppendRequest;
import ru.archlabs.proto.storage.ChatStorageGrpc;
import ru.archlabs.proto.storage.ClearNotificationsRequest;
import ru.archlabs.proto.storage.ConnectRequest;
import ru.archlabs.proto.storage.DisconnectRequest;
import ru.archlabs.proto.storage.HistoryRequest;
import ru.archlabs.proto.storage.SetNotificationsEnabledRequest;
import ru.archlabs.proto.storage.UndeliveredNotificationsRequest;
import ru.archlabs.proto.storage.UserRequest;

import java.sql.DriverManager;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
final class StorageGrpcServiceTest {
    static {
        System.setProperty("api.version", "1.40");
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private PostgresChatStorage storage;
    private io.grpc.Server server;
    private ManagedChannel channel;
    private ChatStorageGrpc.ChatStorageBlockingStub blocking;

    @BeforeEach
    void setUp() throws Exception {
        var name = InProcessServerBuilder.generateName();
        storage =
                new PostgresChatStorage(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        cleanDatabase();
        server =
                InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(new StorageGrpcService(storage))
                        .build()
                        .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        blocking = ChatStorageGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
        storage.close();
    }

    @Test
    void appendAssignsIdAndHistoryReturnsIt() {
        blocking.connect(
                ConnectRequest.newBuilder().setName("ivan").setAt(timestamp(Instant.now())).build());
        var appended =
                blocking.append(
                        AppendRequest.newBuilder()
                                .setSender("ivan")
                                .setText("hello")
                                .setSentAt(timestamp(Instant.now()))
                                .build());

        assertTrue(appended.getId() >= 1);
        assertEquals("ivan", appended.getSender());
        assertEquals("hello", appended.getText());

        var history = blocking.history(HistoryRequest.getDefaultInstance());
        assertEquals(1, history.getMessagesCount());
        assertEquals(appended.getId(), history.getMessages(0).getId());
    }

    @Test
    void connectAndOnlineUsers() {
        blocking.connect(
                ConnectRequest.newBuilder().setName("ivan").setAt(timestamp(Instant.now())).build());

        var online = blocking.onlineUsersWithNotificationsEnabled(Empty.getDefaultInstance());
        assertEquals(1, online.getUsersCount());
        assertTrue(online.getUsers(0).getOnline());
        assertTrue(online.getUsers(0).getNotificationsEnabled());
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
    void undeliveredNotificationsAreReturnedAndCleared() {
        blocking.connect(
                ConnectRequest.newBuilder().setName("sender").setAt(timestamp(Instant.now())).build());
        blocking.connect(
                ConnectRequest.newBuilder().setName("offline").setAt(timestamp(Instant.now())).build());
        blocking.disconnect(
                DisconnectRequest.newBuilder()
                        .setName("offline")
                        .setAt(timestamp(Instant.now()))
                        .build());
        var message =
                blocking.append(
                        AppendRequest.newBuilder()
                                .setSender("sender")
                                .setText("durable")
                                .setSentAt(timestamp(Instant.now()))
                                .build());

        var pending =
                blocking.undeliveredNotifications(
                        UndeliveredNotificationsRequest.newBuilder()
                                .setRecipient("offline")
                                .build());
        assertEquals(1, pending.getMessagesCount());
        assertEquals(message.getId(), pending.getMessages(0).getId());

        blocking.clearNotifications(
                ClearNotificationsRequest.newBuilder()
                        .setRecipient("offline")
                        .addMessageIds(message.getId())
                        .build());
        var cleared =
                blocking.undeliveredNotifications(
                        UndeliveredNotificationsRequest.newBuilder()
                                .setRecipient("offline")
                                .build());
        assertEquals(0, cleared.getMessagesCount());
    }

    @Test
    void resetOnlineUsersMarksEveryoneOffline() {
        blocking.connect(
                ConnectRequest.newBuilder().setName("ivan").setAt(timestamp(Instant.now())).build());

        blocking.resetOnlineUsers(Empty.getDefaultInstance());

        var user = blocking.user(UserRequest.newBuilder().setName("ivan").build());
        assertEquals(false, user.getOnline());
    }

    @Test
    void notificationsSettingIsPersisted() {
        blocking.connect(
                ConnectRequest.newBuilder().setName("ivan").setAt(timestamp(Instant.now())).build());

        blocking.setNotificationsEnabled(
                SetNotificationsEnabledRequest.newBuilder().setName("ivan").setEnabled(false).build());

        var user = blocking.user(UserRequest.newBuilder().setName("ivan").build());
        assertEquals(false, user.getNotificationsEnabled());
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static void cleanDatabase() throws Exception {
        try (var connection =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE messages, users RESTART IDENTITY CASCADE");
        }
    }
}
