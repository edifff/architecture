package ru.archlabs.chat.api;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ru.archlabs.chat.chat.ChatGrpcService;
import ru.archlabs.chat.domain.ChatConflictException;
import ru.archlabs.chat.domain.ChatInternalException;
import ru.archlabs.chat.domain.Message;
import ru.archlabs.chat.metrics.ChatMetrics;
import ru.archlabs.chat.service.ChatServerImpl;
import ru.archlabs.chat.storage.InMemoryChatStorage;

final class ShardedChatGatewayTest {
    private final List<ChatServerImpl> domains = new ArrayList<ChatServerImpl>();
    private final List<Server> servers = new ArrayList<Server>();
    private ShardedChatGateway gateway;

    @BeforeEach
    void setUp() throws Exception {
        Clock clock = new TickingClock();
        List<ChatShard> shards = new ArrayList<ChatShard>();
        for (int index = 0; index < 3; index++) {
            String name = InProcessServerBuilder.generateName();
            ChatServerImpl domain =
                    new ChatServerImpl(
                            new InMemoryChatStorage(index, 3), new ChatMetrics(), clock);
            domains.add(domain);
            servers.add(
                    InProcessServerBuilder.forName(name)
                            .directExecutor()
                            .addService(new ChatGrpcService(domain, "chat-" + index))
                            .build()
                            .start());
            ManagedChannel channel =
                    InProcessChannelBuilder.forName(name).directExecutor().build();
            shards.add(ChatShard.over("chat-" + index, channel));
        }
        gateway = new ShardedChatGateway(shards);
    }

    @AfterEach
    void tearDown() {
        gateway.close();
        for (Server server : servers) {
            server.shutdownNow();
        }
        for (ChatServerImpl domain : domains) {
            domain.close();
        }
    }

    @Test
    void exposesDeterministicShardAssignments() {
        Assertions.assertEquals("chat-2", gateway.shardAssignment("alice").shard());
        Assertions.assertEquals("chat-0", gateway.shardAssignment("bob").shard());
        Assertions.assertEquals("chat-1", gateway.shardAssignment("carol").shard());
        Assertions.assertEquals(3, gateway.shardAssignment("alice").shardCount());
        Assertions.assertEquals(List.of("chat-0", "chat-1", "chat-2"), gateway.shardIds());
    }

    @Test
    void deliversLiveMessagesAcrossShards() throws Exception {
        BlockingQueue<Message> inbox = new LinkedBlockingQueue<Message>();
        gateway.connect("alice");
        gateway.connect("bob");
        AutoCloseable subscription = gateway.subscribe("alice", inbox::add);

        gateway.send("bob", "across the shards");

        Message message = inbox.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals("bob", message.sender());
        Assertions.assertEquals("across the shards", message.text());
        subscription.close();
    }

    @Test
    void queuesMessagesForOfflineUsersOnAnotherShard() throws Exception {
        gateway.connect("alice");
        gateway.connect("bob");
        gateway.disconnect("bob");

        gateway.send("alice", "while you were away");

        BlockingQueue<Message> inbox = new LinkedBlockingQueue<Message>();
        gateway.connect("bob");
        AutoCloseable subscription = gateway.subscribe("bob", inbox::add);
        Message message = inbox.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals("while you were away", message.text());
        subscription.close();
    }

    @Test
    void mergesHistoryFromEveryShardInOrder() {
        gateway.connect("alice");
        gateway.connect("bob");
        gateway.connect("carol");

        gateway.send("alice", "first");
        gateway.send("bob", "second");
        gateway.send("carol", "third");

        List<String> texts =
                gateway.history("alice", 10).stream().map(Message::text).toList();
        Assertions.assertEquals(List.of("first", "second", "third"), texts);
        Assertions.assertEquals(3, gateway.history("alice", 10).size());
    }

    @Test
    void statusFanOutReportsEveryShard() {
        gateway.connect("alice");
        gateway.connect("bob");
        gateway.connect("carol");

        StatusReport report = gateway.broadcastStatus("alice", "maintenance at 22:00");

        Assertions.assertEquals("maintenance at 22:00", report.message().text());
        Assertions.assertEquals(3, report.shardCount());
        Assertions.assertEquals(3, report.chats());
        Assertions.assertEquals(3, report.delivered());
        Assertions.assertEquals(0, report.queued());
        Assertions.assertEquals(3, report.shards().size());
        Assertions.assertEquals(
                List.of("chat-2", "chat-0", "chat-1"),
                report.shards().stream().map(ShardReport::shard).toList());
    }

    @Test
    void statusFanOutQueuesForOfflineUsers() throws Exception {
        gateway.connect("alice");
        gateway.connect("bob");
        gateway.disconnect("bob");

        StatusReport report = gateway.broadcastStatus("alice", "offline status");

        Assertions.assertEquals(2, report.chats());
        Assertions.assertEquals(1, report.delivered());
        Assertions.assertEquals(1, report.queued());

        BlockingQueue<Message> inbox = new LinkedBlockingQueue<Message>();
        gateway.connect("bob");
        AutoCloseable subscription = gateway.subscribe("bob", inbox::add);
        Message message = inbox.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals("offline status", message.text());
        subscription.close();
    }

    @Test
    void handlesMoreConcurrentUsersThanCoresAcrossShards() throws Exception {
        int users = stressThreads();
        int perUser = 2;
        ExecutorService pool = Executors.newFixedThreadPool(users);
        CountDownLatch start = new CountDownLatch(1);
        try {
            ArrayList<Future<?>> futures = new ArrayList<Future<?>>();
            for (int index = 0; index < users; index++) {
                int worker = index;
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    String user = "stress-" + worker;
                                    gateway.connect(user);
                                    for (int message = 0; message < perUser; message++) {
                                        gateway.send(
                                                user, "m-" + worker + "-" + message);
                                    }
                                    return null;
                                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }

            int expected = users * perUser;
            List<Message> history = gateway.history("stress-0", expected + 10);
            Assertions.assertEquals(expected, history.size());
            Set<Long> ids = new HashSet<Long>();
            Set<String> usedShards = new HashSet<String>();
            for (int index = 0; index < users; index++) {
                usedShards.add(gateway.shardAssignment("stress-" + index).shard());
            }
            for (Message message : history) {
                ids.add(message.id());
            }
            Assertions.assertEquals(expected, ids.size());
            Assertions.assertTrue(usedShards.size() > 1, "Stress users must hit several shards");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDuplicateConnectBeyondCoreCountCreatesOneSession() throws Exception {
        int threads = stressThreads();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            ArrayList<Future<String>> futures = new ArrayList<Future<String>>();
            for (int index = 0; index < threads; index++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    try {
                                        gateway.connect("race-user");
                                        return "ok";
                                    } catch (ChatConflictException expected) {
                                        return "rejected";
                                    }
                                }));
            }
            start.countDown();

            int accepted = 0;
            for (Future<String> future : futures) {
                if ("ok".equals(future.get(60, TimeUnit.SECONDS))) {
                    accepted++;
                }
            }
            Assertions.assertEquals(1, accepted);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failsWhenOneShardIsUnavailable() throws Exception {
        gateway.connect("bob");
        Server dead = servers.get(2);
        dead.shutdownNow();
        Assertions.assertTrue(dead.awaitTermination(5, TimeUnit.SECONDS));

        Assertions.assertTrue(gateway.user("bob").online());
        Assertions.assertThrows(
                ChatInternalException.class,
                () -> gateway.broadcastStatus("bob", "degraded"));
        Assertions.assertThrows(ChatInternalException.class, () -> gateway.history("bob", 10));
        Assertions.assertTrue(gateway.user("bob").online());
    }

    private static int stressThreads() {
        // More workers than cores force real context switching.
        return Runtime.getRuntime().availableProcessors() + 4;
    }

    private static final class TickingClock extends Clock {
        private final AtomicLong millis = new AtomicLong(1_000);

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.getAndAdd(10));
        }
    }
}
