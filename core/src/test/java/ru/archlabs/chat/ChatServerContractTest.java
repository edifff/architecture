package ru.archlabs.chat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
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

    abstract void resetDatabase() throws Exception;

    abstract void closeEnvironment() throws Exception;

    @BeforeEach
    void setUp() throws Exception {
        server = createServer();
        resetDatabase();
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
        var ivan = server().connect("ivan", ignored -> {
        });

        var connected = server().user("ivan");
        assertTrue(connected.online());
        assertTrue(connected.notificationsEnabled());
        assertNotNull(connected.lastConnected());

        ivan.close();

        var disconnected = server().user("ivan");
        assertFalse(disconnected.online());
        assertNotNull(disconnected.lastConnected());
    }

    @Test
    void rejectsDuplicateOnlineConnection() {
        server().connect("ivan", ignored -> {
        });

        assertThrows(IllegalStateException.class, () -> server().connect("ivan", ignored -> {
        }));
    }

    @Test
    void deliversToEveryOnlineUserWithNotificationsIncludingSender() throws Exception {
        var ivanInbox = new Inbox();
        var andrayInbox = new Inbox();
        server().connect("ivan", ivanInbox::accept);
        var andray = server().connect("andray", andrayInbox::accept);

        andray.send("hello");

        var toIvan = ivanInbox.await();
        var toAndray = andrayInbox.await();
        assertNotNull(toIvan);
        assertNotNull(toAndray);
        assertEquals("andray", toIvan.sender());
        assertEquals("hello", toIvan.text());
        assertEquals(toIvan, toAndray);
    }

    @Test
    void skipsOfflineAndNotificationsDisabledUsers() throws Exception {
        var offlineInbox = new Inbox();
        var silentInbox = new Inbox();
        var offline = server().connect("offline", offlineInbox::accept);
        var silent = server().connect("silent", silentInbox::accept);
        var sender = server().connect("sender", ignored -> {
        });
        offline.close();
        silent.setNotificationsEnabled(false);

        sender.send("hello");

        assertNull(offlineInbox.poll());
        assertNull(silentInbox.poll());
    }

    @Test
    void offlineUserReceivesAccumulatedNotificationsAfterReconnect() throws Exception {
        var offlineInbox = new Inbox();
        server().connect("offline", offlineInbox::accept).close();
        server().connect("sender", ignored -> {
        }).send("hello");
        server().connect("offline", offlineInbox::accept);

        var message = offlineInbox.await();
        assertNotNull(message);
        assertEquals("sender", message.sender());
        assertEquals("hello", message.text());
    }

    @Test
    void disabledUserCanReadCompleteOrderedHistory() {
        var ivan = server().connect("ivan", ignored -> {
        });
        var andray = server().connect("andray", ignored -> {
        });
        ivan.setNotificationsEnabled(false);

        andray.send("first");
        ivan.send("second");

        var history = ivan.history();
        assertEquals(List.of(1L, 2L), history.stream().map(Message::id).toList());
        assertEquals(List.of("first", "second"), history.stream().map(Message::text).toList());
    }

    @Test
    void rejectsBlankUsername() {
        assertThrows(
                IllegalArgumentException.class,
                () -> server().connect("   ", ignored -> {
                }));
    }

    @Test
    void rejectsBlankMessage() {
        var ivan = server().connect("ivan", ignored -> {
        });

        assertThrows(IllegalArgumentException.class, () -> ivan.send("   "));
    }

    @Test
    void rejectsOperationsAfterSessionClosed() {
        var ivan = server().connect("ivan", ignored -> {
        });
        ivan.close();

        assertThrows(IllegalStateException.class, () -> ivan.send("hello"));
        assertThrows(IllegalStateException.class, ivan::history);
        assertThrows(IllegalStateException.class, () -> ivan.setNotificationsEnabled(false));
    }

    @Test
    void closingSessionTwiceIsIdempotent() {
        var ivan = server().connect("ivan", ignored -> {
        });
        ivan.close();
        ivan.close();

        assertFalse(server().user("ivan").online());
    }

    @Test
    void unknownUserStateThrows() {
        assertThrows(IllegalArgumentException.class, () -> server().user("nobody"));
    }

    @Test
    void concurrentConnectAllowsExactlyOne() throws Exception {
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

    private String attemptConnect(CyclicBarrier barrier) throws Exception {
        barrier.await();
        try {
            server().connect("race", ignored -> {
            });
            return "ok";
        } catch (IllegalStateException expected) {
            return "rejected";
        }
    }
}
