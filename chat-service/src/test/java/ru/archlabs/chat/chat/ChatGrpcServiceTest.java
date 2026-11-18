package ru.archlabs.chat.chat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ru.archlabs.chat.service.ChatServerImpl;
import ru.archlabs.proto.chat.ChatServiceGrpc;
import ru.archlabs.proto.chat.ConnectRequest;
import ru.archlabs.proto.chat.DeliverRequest;
import ru.archlabs.proto.chat.DeliveryReport;
import ru.archlabs.proto.chat.HistoryRequest;
import ru.archlabs.proto.chat.LocalHistoryRequest;
import ru.archlabs.proto.chat.SendMessageRequest;
import ru.archlabs.proto.chat.SetNotificationsRequest;
import ru.archlabs.proto.chat.StatusSendResponse;
import ru.archlabs.proto.chat.UserRequest;
import ru.archlabs.proto.common.ChatMessage;
import ru.archlabs.proto.common.UserState;


final class ChatGrpcServiceTest {
    private ChatServerImpl domain;
    private Server server;
    private ManagedChannel channel;
    private ChatServiceGrpc.ChatServiceBlockingStub chat;
    private ChatServiceGrpc.ChatServiceStub async;

    @BeforeEach
    void setUp() throws Exception {
        String name = InProcessServerBuilder.generateName();
        domain = new ChatServerImpl();
        server =
                InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(new ChatGrpcService(domain))
                        .build()
                        .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        chat = ChatServiceGrpc.newBlockingStub(channel);
        async = ChatServiceGrpc.newStub(channel);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
        domain.close();
    }

    @Test
    void exposesDomainContractOverGrpc() {
        Assertions.assertTrue(connect("alice").getOnline());

        ChatMessage message =
                chat.send(
                        SendMessageRequest.newBuilder()
                                .setName("alice")
                                .setText("hello")
                                .build());
        Assertions.assertEquals("hello", message.getText());
        Assertions.assertEquals(
                1,
                chat.history(
                                HistoryRequest.newBuilder()
                                        .setName("alice")
                                        .setLimit(10)
                                        .build())
                        .getMessagesCount());

        chat.setNotifications(
                SetNotificationsRequest.newBuilder().setName("alice").setEnabled(false).build());
        Assertions.assertFalse(
                chat.getUser(UserRequest.newBuilder().setName("alice").build())
                        .getNotificationsEnabled());
    }

    @Test
    void streamsLiveMessages() throws Exception {
        connect("alice");
        connect("bob");
        Iterator<ChatMessage> iterator = chat.subscribe(UserRequest.newBuilder().setName("alice").build());
        CompletableFuture<ChatMessage> next = CompletableFuture.supplyAsync(iterator::next);

        chat.send(SendMessageRequest.newBuilder().setName("bob").setText("live").build());

        ChatMessage message = next.get(5, TimeUnit.SECONDS);
        Assertions.assertEquals("bob", message.getSender());
        Assertions.assertEquals("live", message.getText());
    }

    @Test
    void deliversRemoteMessageToLocalSessions() throws Exception {
        connect("alice");
        Iterator<ChatMessage> iterator =
                chat.subscribe(UserRequest.newBuilder().setName("alice").build());
        CompletableFuture<ChatMessage> next = CompletableFuture.supplyAsync(iterator::next);

        DeliveryReport report =
                chat.deliver(
                        DeliverRequest.newBuilder()
                                .setMessage(
                                        ChatMessage.newBuilder()
                                                .setId(55)
                                                .setSender("bob")
                                                .setText("remote")
                                                .setCreatedAtEpochMillis(1)
                                                .build())
                                .build());

        Assertions.assertEquals("local", report.getShard());
        Assertions.assertEquals(1, report.getChats());
        Assertions.assertEquals(1, report.getDelivered());
        Assertions.assertEquals("bob", next.get(5, TimeUnit.SECONDS).getSender());
        Assertions.assertEquals("remote", next.get(5, TimeUnit.SECONDS).getText());
    }

    @Test
    void statusReportsEveryLocalChat() {
        connect("alice");
        connect("bob");

        StatusSendResponse response =
                chat.sendStatus(
                        SendMessageRequest.newBuilder()
                                .setName("alice")
                                .setText("maintenance")
                                .build());

        Assertions.assertEquals("maintenance", response.getMessage().getText());
        Assertions.assertEquals(2, response.getDelivery().getChats());
        Assertions.assertEquals(2, response.getDelivery().getDelivered());
        Assertions.assertEquals(0, response.getDelivery().getQueued());
    }

    @Test
    void localHistoryReturnsStoredMessages() {
        connect("alice");
        chat.send(
                SendMessageRequest.newBuilder().setName("alice").setText("stored").build());

        Assertions.assertEquals(
                1,
                chat.localHistory(LocalHistoryRequest.newBuilder().setLimit(10).build())
                        .getMessagesCount());
    }

    @Test
    void concurrentSendsBeyondCoreCountKeepEveryMessage() throws Exception {
        connect("alice");
        connect("bob");
        int threads = stressThreads();
        int perThread = 5;
        int expected = threads * perThread;
        CountDownLatch delivered = new CountDownLatch(expected);
        AtomicInteger received = new AtomicInteger();
        subscribe("alice", received, delivered);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<List<Long>>> futures =
                    sendConcurrently(pool, start, threads, perThread);
            start.countDown();

            Assertions.assertEquals(expected, collectIds(futures).size());
            Assertions.assertTrue(delivered.await(30, TimeUnit.SECONDS));
            Assertions.assertEquals(expected, received.get());
            Assertions.assertEquals(expected, historyCount(expected + 10));
        } finally {
            pool.shutdownNow();
        }
    }

    private void subscribe(String user, AtomicInteger received, CountDownLatch delivered) {
        async.subscribe(
                UserRequest.newBuilder().setName(user).build(),
                new StreamObserver<ChatMessage>() {
                    @Override
                    public void onNext(ChatMessage value) {
                        received.incrementAndGet();
                        delivered.countDown();
                    }

                    @Override
                    public void onError(Throwable throwable) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                });
    }

    private List<Future<List<Long>>> sendConcurrently(
            ExecutorService pool, CountDownLatch start, int threads, int perThread) {
        ArrayList<Future<List<Long>>> futures = new ArrayList<Future<List<Long>>>();
        for (int index = 0; index < threads; index++) {
            int worker = index;
            futures.add(
                    pool.submit(
                            () -> {
                                start.await();
                                ArrayList<Long> ids = new ArrayList<Long>();
                                for (int message = 0; message < perThread; message++) {
                                    ids.add(
                                            chat.send(
                                                            SendMessageRequest.newBuilder()
                                                                    .setName("bob")
                                                                    .setText(
                                                                            "stress-" + worker
                                                                                    + "-" + message)
                                                                    .build())
                                                    .getId());
                                }
                                return ids;
                            }));
        }
        return futures;
    }

    private static Set<Long> collectIds(List<Future<List<Long>>> futures) throws Exception {
        Set<Long> ids = new HashSet<Long>();
        for (Future<List<Long>> future : futures) {
            ids.addAll(future.get(120, TimeUnit.SECONDS));
        }
        return ids;
    }

    private int historyCount(int limit) {
        return chat.history(
                        HistoryRequest.newBuilder().setName("alice").setLimit(limit).build())
                .getMessagesCount();
    }

    @Test
    void duplicateConnectionMapsToAlreadyExists() {
        connect("alice");

        StatusRuntimeException error =
                Assertions.assertThrows(
                        StatusRuntimeException.class,
                        () -> connect("alice"));
        Assertions.assertEquals(Status.Code.ALREADY_EXISTS, error.getStatus().getCode());
    }

    @Test
    void disconnectMarksUserOffline() {
        connect("alice");
        chat.disconnect(UserRequest.newBuilder().setName("alice").build());

        Assertions.assertFalse(
                chat.getUser(UserRequest.newBuilder().setName("alice").build()).getOnline());
    }

    private UserState connect(String user) {
        return chat.connect(ConnectRequest.newBuilder().setName(user).build());
    }

    private static int stressThreads() {
        // More workers than cores force real context switching.
        return Runtime.getRuntime().availableProcessors() + 4;
    }
}
