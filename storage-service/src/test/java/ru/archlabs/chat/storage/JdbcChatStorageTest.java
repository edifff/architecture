package ru.archlabs.chat.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ru.archlabs.chat.domain.Message;
import ru.archlabs.chat.domain.Notification;


final class JdbcChatStorageTest {
    @TempDir
    private Path tempDir;

    @Test
    void committedMessagesAndNotificationsSurviveReopen() {
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        JdbcChatStorage first = new JdbcChatStorage(url());
        first.initialize();
        first.connect("alice", at);
        first.appendMessage("bob", "hello", List.of("alice"), at);

        JdbcChatStorage second = new JdbcChatStorage(url());
        second.initialize();

        Assertions.assertEquals(1, second.history(10).size());
        Assertions.assertEquals("hello", second.history(10).getFirst().text());
        Assertions.assertEquals(1, second.pendingNotifications("alice").size());
        Assertions.assertTrue(second.findUser("alice").orElseThrow().online());
    }

    @Test
    void resetOnlineUsersIsTheCrashRecoveryBoundary() {
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        JdbcChatStorage first = new JdbcChatStorage(url());
        first.initialize();
        first.connect("alice", at);

        JdbcChatStorage second = new JdbcChatStorage(url());
        second.initialize();
        second.resetOnlineUsers();

        Assertions.assertFalse(second.findUser("alice").orElseThrow().online());
    }

    @Test
    void deliveredNotificationIsRemovedFromPending() {
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        JdbcChatStorage storage = new JdbcChatStorage(url());
        storage.initialize();
        storage.connect("alice", at);
        Message message = storage.appendMessage("bob", "hello", List.of("alice"), at);
        Notification notification = storage.pendingNotifications("alice").getFirst();

        storage.markNotificationDelivered(notification.id(), at);

        Assertions.assertTrue(storage.pendingNotifications("alice").isEmpty());
        Assertions.assertEquals(new Message(message.id(), "bob", "hello", at), message);
    }

    @Test
    void shardsUseDisjointIdentitySequences() {
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        JdbcChatStorage first = new JdbcChatStorage(url("shard-0"), 0, 3);
        first.initialize();
        JdbcChatStorage second = new JdbcChatStorage(url("shard-1"), 1, 3);
        second.initialize();

        Message firstMessage = first.appendMessage("alice", "a", List.of(), at);
        Message secondMessage = second.appendMessage("bob", "b", List.of(), at);
        Message thirdMessage = first.appendMessage("alice", "c", List.of(), at);

        Assertions.assertEquals(1, firstMessage.id());
        Assertions.assertEquals(2, secondMessage.id());
        Assertions.assertEquals(4, thirdMessage.id());
        Assertions.assertEquals(2, first.history(10).size());
        Assertions.assertEquals(1, second.history(10).size());
    }

    @Test
    void notificationsCarryTheMessageForCrossShardDelivery() {
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        JdbcChatStorage storage = new JdbcChatStorage(url());
        storage.initialize();
        storage.connect("alice", at);
        Message remote = new Message(1234, "bob", "from another shard", at);

        storage.enqueueNotification("alice", remote, at);

        JdbcChatStorage reopened = new JdbcChatStorage(url());
        reopened.initialize();
        Notification notification = reopened.pendingNotifications("alice").getFirst();
        Assertions.assertEquals(remote, notification.message());
    }

    @Test
    void legacyNotificationSchemaIsMigrated() throws Exception {
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        try (Connection connection = DriverManager.getConnection(url(), "sa", "");
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE messages (id BIGINT PRIMARY KEY, sender VARCHAR(64), "
                            + "body VARCHAR(4000), created_at BIGINT)");
            statement.execute(
                    "CREATE TABLE notifications (id BIGINT PRIMARY KEY, recipient VARCHAR(64), "
                            + "message_id BIGINT, created_at BIGINT, delivered_at BIGINT, "
                            + "CONSTRAINT fk_notifications_message "
                            + "FOREIGN KEY (message_id) REFERENCES messages(id))");
            statement.execute(
                    "INSERT INTO messages VALUES (1, 'bob', 'legacy', " + at.toEpochMilli() + ")");
            statement.execute(
                    "INSERT INTO notifications VALUES (1, 'alice', 1, " + at.toEpochMilli()
                            + ", NULL)");
        }

        JdbcChatStorage storage = new JdbcChatStorage(url());
        storage.initialize();

        Notification notification = storage.pendingNotifications("alice").getFirst();
        Assertions.assertEquals("bob", notification.message().sender());
        Assertions.assertEquals("legacy", notification.message().text());
    }

    private String url() {
        return url("storage");
    }

    private String url(String name) {
        return "jdbc:h2:file:"
                + tempDir.resolve(name).toAbsolutePath()
                + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
    }
}
