package ru.archlabs.chat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ru.archlabs.chat.domain.ChatConflictException;
import ru.archlabs.chat.domain.ChatNotFoundException;
import ru.archlabs.chat.domain.ChatUser;
import ru.archlabs.chat.domain.ChatValidationException;
import ru.archlabs.chat.domain.Message;


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
        ChatSession alice = server().connect("alice");

        ChatUser connected = server().user("alice");
        Assertions.assertTrue(connected.online());
        Assertions.assertTrue(connected.notificationsEnabled());
        Assertions.assertNotNull(connected.lastSeen());

        alice.close();

        ChatUser disconnected = server().user("alice");
        Assertions.assertFalse(disconnected.online());
    }

    @Test
    void rejectsDuplicateOnlineConnection() {
        server().connect("alice");

        Assertions.assertThrows(ChatConflictException.class, () -> server().connect("alice"));
    }

    @Test
    void deliversLiveMessagesToEveryEnabledConnectedUserIncludingSender() throws Exception {
        ChatServerContractTest.Inbox aliceInbox = new Inbox();
        ChatServerContractTest.Inbox bobInbox = new Inbox();
        server().connect("alice").addListener(aliceInbox::accept);
        ChatSession bob = server().connect("bob");
        bob.addListener(bobInbox::accept);

        bob.send("hello");

        Message toAlice = aliceInbox.await();
        Message toBob = bobInbox.await();
        Assertions.assertNotNull(toAlice);
        Assertions.assertNotNull(toBob);
        Assertions.assertEquals("bob", toAlice.sender());
        Assertions.assertEquals("hello", toAlice.text());
        Assertions.assertEquals(toAlice, toBob);
    }

    @Test
    void disabledUserDoesNotReceiveLiveNotificationsButCanReadHistory() throws Exception {
        ChatServerContractTest.Inbox aliceInbox = new Inbox();
        ChatSession alice = server().connect("alice");
        alice.addListener(aliceInbox::accept);
        ChatSession bob = server().connect("bob");

        alice.setNotificationsEnabled(false);
        bob.send("hidden from live stream");

        Assertions.assertNull(aliceInbox.poll());
        List<Message> history = alice.history(10);
        Assertions.assertEquals(1, history.size());
        Assertions.assertEquals("hidden from live stream", history.getFirst().text());
    }

    @Test
    void offlineUserReceivesBufferedNotificationsAfterReconnect() throws Exception {
        ChatServerContractTest.Inbox aliceInbox = new Inbox();
        server().connect("alice").close();
        server().connect("bob").send("while you were away");

        ChatSession alice = server().connect("alice");
        alice.addListener(aliceInbox::accept);

        Message message = aliceInbox.await();
        Assertions.assertNotNull(message);
        Assertions.assertEquals("bob", message.sender());
        Assertions.assertEquals("while you were away", message.text());
    }

    @Test
    void disabledOfflineUserCanEnableNotificationsAndReceiveBufferedMessages() throws Exception {
        ChatSession alice = server().connect("alice");
        alice.setNotificationsEnabled(false);
        alice.close();
        server().connect("bob").send("later");

        ChatServerContractTest.Inbox inbox = new Inbox();
        ChatSession reconnected = server().connect("alice");
        reconnected.addListener(inbox::accept);
        Assertions.assertNull(inbox.poll());

        reconnected.setNotificationsEnabled(true);
        Assertions.assertNotNull(inbox.await());
    }

    @Test
    void listenerAttachedAfterMessageStillReceivesBufferedMessage() throws Exception {
        ChatSession alice = server().connect("alice");
        ChatSession bob = server().connect("bob");

        bob.send("early");
        ChatServerContractTest.Inbox inbox = new Inbox();
        alice.addListener(inbox::accept);

        Assertions.assertNotNull(inbox.await());
    }

    @Test
    void historyIsOrderedAndLimited() {
        ChatSession alice = server().connect("alice");
        alice.send("one");
        alice.send("two");
        alice.send("three");

        List<Message> all = alice.history(10);
        Assertions.assertEquals(List.of("one", "two", "three"), all.stream().map(Message::text).toList());
        Assertions.assertEquals(List.of("three"), alice.history(1).stream().map(Message::text).toList());
    }

    @Test
    void rejectsBlankInput() {
        Assertions.assertThrows(ChatValidationException.class, () -> server().connect("   "));
        ChatSession alice = server().connect("alice");
        Assertions.assertThrows(ChatValidationException.class, () -> alice.send("   "));
        Assertions.assertThrows(ChatValidationException.class, () -> alice.history(0));
    }

    @Test
    void rejectsOperationsAfterSessionClosed() {
        ChatSession alice = server().connect("alice");
        alice.close();

        Assertions.assertThrows(ChatConflictException.class, () -> alice.send("hello"));
        Assertions.assertThrows(ChatConflictException.class, () -> alice.history(10));
        Assertions.assertThrows(ChatConflictException.class, () -> alice.setNotificationsEnabled(false));
    }

    @Test
    void closingSessionTwiceIsIdempotent() {
        ChatSession alice = server().connect("alice");
        alice.close();
        alice.close();

        Assertions.assertFalse(server().user("alice").online());
    }

    @Test
    void unknownUserStateThrows() {
        Assertions.assertThrows(ChatNotFoundException.class, () -> server().user("nobody"));
    }

    @Test
    void concurrentConnectAllowsExactlyOneSession() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> attemptConnect(barrier));
            Future<String> second = pool.submit(() -> attemptConnect(barrier));
            List<String> outcomes =
                    List.of(
                            first.get(5, TimeUnit.SECONDS),
                            second.get(5, TimeUnit.SECONDS));

            Assertions.assertEquals(1, outcomes.stream().filter("ok"::equals).count());
            Assertions.assertEquals(1, outcomes.stream().filter("rejected"::equals).count());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSendsDoNotLoseMessagesOrIds() throws Exception {
        int senders = concurrentSenders();
        int perSender = messagesPerSender();
        ExecutorService pool = Executors.newFixedThreadPool(senders);
        try {
            ArrayList<ChatSession> sessions = new ArrayList<ChatSession>();
            for (int i = 0; i < senders; i++) {
                sessions.add(server().connect("user-" + i));
            }
            ArrayList<Future<?>> futures = new ArrayList<Future<?>>();
            for (ChatSession session : sessions) {
                futures.add(
                        pool.submit(
                                () -> {
                                    for (int i = 0; i < perSender; i++) {
                                        session.send(session.userName() + "-" + i);
                                    }
                                }));
            }
            for (Future<?> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }

            List<Message> history = server().history(10_000);
            Assertions.assertEquals(senders * perSender, history.size());
            Assertions.assertEquals(history.size(), new HashSet<>(history.stream().map(Message::id).toList()).size());
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
        private final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();

        void accept(Message message) {
            messages.add(message);
        }

        Message await() throws InterruptedException {
            return messages.poll(2, TimeUnit.SECONDS);
        }

        Message poll() {
            return messages.poll();
        }
    }
}
