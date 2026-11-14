package ru.archlabs.chat.storage;

import java.nio.file.Path;
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

    private String url() {
        return "jdbc:h2:file:"
                + tempDir.resolve("storage").toAbsolutePath()
                + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
    }
}
