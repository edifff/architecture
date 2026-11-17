package ru.archlabs.chat.api;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
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
