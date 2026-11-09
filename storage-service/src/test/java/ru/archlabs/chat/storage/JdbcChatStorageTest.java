package ru.archlabs.chat.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.archlabs.chat.domain.ChatMessage;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JdbcChatStorageTest {
    @TempDir
    Path tempDir;

    @Test
    void committedMessagesAndNotificationsSurviveReopen() {
        var at = Instant.parse("2026-01-01T00:00:00Z");
        var first = new JdbcChatStorage(url());
        first.initialize();
        first.connect("alice", at);
        first.appendMessage("bob", "hello", List.of("alice"), at);

        var second = new JdbcChatStorage(url());
        second.initialize();

        assertEquals(1, second.history(10).size());
        assertEquals("hello", second.history(10).getFirst().text());
        assertEquals(1, second.pendingNotifications("alice").size());
        assertTrue(second.findUser("alice").orElseThrow().online());
    }

    @Test
    void resetOnlineUsersIsTheCrashRecoveryBoundary() {
        var at = Instant.parse("2026-01-01T00:00:00Z");
        var first = new JdbcChatStorage(url());
        first.initialize();
        first.connect("alice", at);

        var second = new JdbcChatStorage(url());
        second.initialize();
        second.resetOnlineUsers();

        assertFalse(second.findUser("alice").orElseThrow().online());
    }

    @Test
    void deliveredNotificationIsRemovedFromPending() {
        var at = Instant.parse("2026-01-01T00:00:00Z");
        var storage = new JdbcChatStorage(url());
        storage.initialize();
        storage.connect("alice", at);
        var message = storage.appendMessage("bob", "hello", List.of("alice"), at);
        var notification = storage.pendingNotifications("alice").getFirst();

        storage.markNotificationDelivered(notification.id(), at);

        assertTrue(storage.pendingNotifications("alice").isEmpty());
        assertEquals(new ChatMessage(message.id(), "bob", "hello", at), message);
    }

    private String url() {
        return "jdbc:h2:file:"
                + tempDir.resolve("storage").toAbsolutePath()
                + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
    }
}
