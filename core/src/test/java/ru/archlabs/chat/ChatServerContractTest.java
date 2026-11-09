package ru.archlabs.chat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.archlabs.chat.domain.ChatConflictException;
import ru.archlabs.chat.domain.ChatMessage;
import ru.archlabs.chat.domain.ChatNotFoundException;
import ru.archlabs.chat.domain.ChatValidationException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class ChatServerContractTest {
    private ChatServer server;

    abstract ChatServer createServer() throws Exception;

    abstract void closeEnvironment() throws Exception;

    @BeforeEach
    void setUp() throws Exception {
        server = createServer();
    }

    @AfterEach
    void tearDown() throws Exception {
        closeEnvironment();
    }

    final ChatServer server() {
        return server;
    }

    @Test
    void tracksConnectionAndDisconnection() {
        var alice = server().connect("alice");

        var connected = server().user("alice");
        assertTrue(connected.online());
        assertTrue(connected.notificationsEnabled());
        assertNotNull(connected.lastSeen());

        alice.close();

        var disconnected = server().user("alice");
        assertFalse(disconnected.online());
    }

    @Test
    void rejectsDuplicateOnlineConnection() {
        server().connect("alice");

        assertThrows(ChatConflictException.class, () -> server().connect("alice"));
    }

    @Test
    void deliversLiveMessagesToEveryEnabledConnectedUserIncludingSender() throws Exception {
        var aliceInbox = new Inbox();
        var bobInbox = new Inbox();
        server().connect("alice").addListener(aliceInbox::accept);
        var bob = server().connect("bob");
        bob.addListener(bobInbox::accept);

        bob.send("hello");

        var toAlice = aliceInbox.await();
        var toBob = bobInbox.await();
        assertNotNull(toAlice);
        assertNotNull(toBob);
        assertEquals("bob", toAlice.sender());
        assertEquals("hello", toAlice.text());
        assertEquals(toAlice, toBob);
    }

    @Test
    void disabledUserDoesNotReceiveLiveNotificationsButCanReadHistory() throws Exception {
        var aliceInbox = new Inbox();
        var alice = server().connect("alice");
        alice.addListener(aliceInbox::accept);
        var bob = server().connect("bob");

        alice.setNotificationsEnabled(false);
        bob.send("hidden from live stream");

        assertNull(aliceInbox.poll());
        var history = alice.history(10);
        assertEquals(1, history.size());
        assertEquals("hidden from live stream", history.getFirst().text());
    }

    @Test
    void offlineUserReceivesBufferedNotificationsAfterReconnect() throws Exception {
        var aliceInbox = new Inbox();
        server().connect("alice").close();
        server().connect("bob").send("while you were away");

        var alice = server().connect("alice");
        alice.addListener(aliceInbox::accept);

        var message = aliceInbox.await();
        assertNotNull(message);
        assertEquals("bob", message.sender());
        assertEquals("while you were away", message.text());
    }

    @Test
    void disabledOfflineUserCanEnableNotificationsAndReceiveBufferedMessages() throws Exception {
        var alice = server().connect("alice");
        alice.setNotificationsEnabled(false);
        alice.close();
        server().connect("bob").send("later");

        var inbox = new Inbox();
        var reconnected = server().connect("alice");
        reconnected.addListener(inbox::accept);
        assertNull(inbox.poll());

        reconnected.setNotificationsEnabled(true);
        assertNotNull(inbox.await());
    }

    @Test
    void listenerAttachedAfterMessageStillReceivesBufferedMessage() throws Exception {
        var alice = server().connect("alice");
        var bob = server().connect("bob");

        bob.send("early");
        var inbox = new Inbox();
        alice.addListener(inbox::accept);

        assertNotNull(inbox.await());
    }

    @Test
    void historyIsOrderedAndLimited() {
        var alice = server().connect("alice");
        alice.send("one");
        alice.send("two");
        alice.send("three");

        var all = alice.history(10);
        assertEquals(List.of("one", "two", "three"), all.stream().map(ChatMessage::text).toList());
        assertEquals(List.of("three"), alice.history(1).stream().map(ChatMessage::text).toList());
    }

    @Test
    void rejectsBlankInput() {
        assertThrows(ChatValidationException.class, () -> server().connect("   "));
        var alice = server().connect("alice");
        assertThrows(ChatValidationException.class, () -> alice.send("   "));
        assertThrows(ChatValidationException.class, () -> alice.history(0));
    }

    @Test
    void rejectsOperationsAfterSessionClosed() {
        var alice = server().connect("alice");
        alice.close();

        assertThrows(ChatConflictException.class, () -> alice.send("hello"));
        assertThrows(ChatConflictException.class, () -> alice.history(10));
        assertThrows(ChatConflictException.class, () -> alice.setNotificationsEnabled(false));
    }

    @Test
    void closingSessionTwiceIsIdempotent() {
        var alice = server().connect("alice");
        alice.close();
        alice.close();

        assertFalse(server().user("alice").online());
    }

    @Test
    void unknownUserStateThrows() {
        assertThrows(ChatNotFoundException.class, () -> server().user("nobody"));
    }

    @Test
    void concurrentConnectAllowsExactlyOneSession() throws Exception {
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> attemptConnect(barrier));
            var second = pool.submit(() -> attemptConnect(barrier));
            var outcomes =
                    List.of(
                            first.get(5, TimeUnit.SECONDS),
                            second.get(5, TimeUnit.SECONDS));

            assertEquals(1, outcomes.stream().filter("ok"::equals).count());
            assertEquals(1, outcomes.stream().filter("rejected"::equals).count());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSendsDoNotLoseMessagesOrIds() throws Exception {
        var senders = concurrentSenders();
        var perSender = messagesPerSender();
        var pool = Executors.newFixedThreadPool(senders);
        try {
            var sessions = new ArrayList<ChatSession>();
            for (var i = 0; i < senders; i++) {
                sessions.add(server().connect("user-" + i));
            }
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (var session : sessions) {
                futures.add(
                        pool.submit(
                                () -> {
                                    for (var i = 0; i < perSender; i++) {
                                        session.send(session.userName() + "-" + i);
                                    }
                                }));
            }
            for (var future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }

            var history = server().history(10_000);
            assertEquals(senders * perSender, history.size());
            assertEquals(history.size(), new HashSet<>(history.stream().map(ChatMessage::id).toList()).size());
        } finally {
            pool.shutdownNow();
        }
    }

    int concurrentSenders() {
        return 8;
    }

    int messagesPerSender() {
        return 40;
    }

    private String attemptConnect(CyclicBarrier barrier) throws Exception {
        barrier.await();
        try {
            server().connect("race");
            return "ok";
        } catch (ChatConflictException expected) {
            return "rejected";
        }
    }

    static final class Inbox {
        private final BlockingQueue<ChatMessage> messages = new LinkedBlockingQueue<>();

        void accept(ChatMessage message) {
            messages.add(message);
        }

        ChatMessage await() throws InterruptedException {
            return messages.poll(2, TimeUnit.SECONDS);
        }

        ChatMessage poll() {
            return messages.poll();
        }
    }
}
