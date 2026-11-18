package ru.archlabs.chat;

import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ru.archlabs.chat.domain.ChatUser;
import ru.archlabs.chat.service.ChatServerImpl;
import ru.archlabs.chat.storage.JdbcChatStorage;


final class PersistentChatServerTest extends ChatServerContractTest {
    @TempDir
    private Path tempDir;

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

    @Override
    int stressMessagesPerSender() {
        return 1;
    }

    @Test
    void normalRestartKeepsStateAndBufferedNotifications() throws Exception {
        ChatServerImpl first = new ChatServerImpl(new JdbcChatStorage(url()));
        ChatSession alice = first.connect("alice");
        alice.setNotificationsEnabled(false);
        alice.close();
        first.connect("bob").send("persisted while disabled");
        first.close();

        try (ChatServerImpl second = new ChatServerImpl(new JdbcChatStorage(url()))) {
            ChatUser state = second.user("alice");
            Assertions.assertFalse(state.online());
            Assertions.assertFalse(state.notificationsEnabled());
            Assertions.assertEquals(1, second.history(10).size());

            ChatServerContractTest.Inbox inbox = new Inbox();
            ChatSession reconnected = second.connect("alice");
            reconnected.addListener(inbox::accept);
            Assertions.assertNull(inbox.poll());

            reconnected.setNotificationsEnabled(true);
            Assertions.assertNotNull(inbox.await());
        }
    }

    @Test
    void crashRestartMarksUsersOfflineAndKeepsNotificationsPending() throws Exception {
        ChatServerImpl first = new ChatServerImpl(new JdbcChatStorage(url()));
        first.connect("alice").close();
        first.connect("bob").send("sent before crash");

        try (ChatServerImpl second = new ChatServerImpl(new JdbcChatStorage(url()))) {
            Assertions.assertFalse(second.user("alice").online());

            ChatServerContractTest.Inbox inbox = new Inbox();
            second.connect("alice").addListener(inbox::accept);
            Assertions.assertNotNull(inbox.await());
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
