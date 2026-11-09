package ru.archlabs.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.archlabs.chat.service.ChatServerImpl;
import ru.archlabs.chat.storage.JdbcChatStorage;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

final class PersistentChatServerTest extends ChatServerContractTest {
    @TempDir
    Path tempDir;

    private ChatServer server;

    @Override
    ChatServer createServer() {
        server = new ChatServerImpl(new JdbcChatStorage(url()));
        return server;
    }

    @Override
    void closeEnvironment() {
        if (server != null) {
            server.close();
        }
    }

    @Override
    int concurrentSenders() {
        return 4;
    }

    @Override
    int messagesPerSender() {
        return 10;
    }

    @Test
    void normalRestartKeepsStateAndBufferedNotifications() throws Exception {
        var first = new ChatServerImpl(new JdbcChatStorage(url()));
        var alice = first.connect("alice");
        alice.setNotificationsEnabled(false);
        alice.close();
        first.connect("bob").send("persisted while disabled");
        first.close();

        try (var second = new ChatServerImpl(new JdbcChatStorage(url()))) {
            var state = second.user("alice");
            assertFalse(state.online());
            assertFalse(state.notificationsEnabled());
            assertEquals(1, second.history(10).size());

            var inbox = new Inbox();
            var reconnected = second.connect("alice");
            reconnected.addListener(inbox::accept);
            assertNull(inbox.poll());

            reconnected.setNotificationsEnabled(true);
            assertNotNull(inbox.await());
        }
    }

    @Test
    void crashRestartMarksUsersOfflineAndKeepsNotificationsPending() throws Exception {
        var first = new ChatServerImpl(new JdbcChatStorage(url()));
        first.connect("alice").close();
        first.connect("bob").send("sent before crash");

        try (var second = new ChatServerImpl(new JdbcChatStorage(url()))) {
            assertFalse(second.user("alice").online());

            var inbox = new Inbox();
            second.connect("alice").addListener(inbox::accept);
            assertNotNull(inbox.await());
        } finally {
            first.close();
        }
    }

    private String url() {
        return "jdbc:h2:file:"
                + tempDir.resolve("chat").toAbsolutePath()
                + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
    }
}
