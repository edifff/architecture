package ru.archlabs.chat.api;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ru.archlabs.chat.chat.ChatGrpcService;
import ru.archlabs.chat.domain.ChatNotFoundException;
import ru.archlabs.chat.domain.Message;
import ru.archlabs.chat.service.ChatServerImpl;


final class GrpcChatGatewayTest {
    private ChatServerImpl domain;
    private Server server;
    private ManagedChannel channel;
    private GrpcChatGateway gateway;

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
        gateway = new GrpcChatGateway(channel);
    }

    @AfterEach
    void tearDown() {
        gateway.close();
        server.shutdownNow();
        domain.close();
    }

    @Test
    void mapsTheChatContractToDomainRecords() {
        Assertions.assertTrue(gateway.connect("alice").online());

        Message message = gateway.send("alice", "hello");
        Assertions.assertEquals("hello", message.text());
        Assertions.assertEquals(1, gateway.history("alice", 10).size());

        gateway.setNotificationsEnabled("alice", false);
        Assertions.assertFalse(gateway.user("alice").notificationsEnabled());

        gateway.disconnect("alice");
        Assertions.assertFalse(gateway.user("alice").online());
    }

    @Test
    void mapsGrpcNotFoundToDomainException() {
        Assertions.assertThrows(ChatNotFoundException.class, () -> gateway.user("ghost"));
    }

    @Test
    void subscribesToLiveMessages() throws Exception {
        gateway.connect("alice");
        gateway.connect("bob");
        LinkedBlockingQueue<Message> inbox = new LinkedBlockingQueue<Message>();

        AutoCloseable subscription = gateway.subscribe("alice", inbox::add);
        gateway.send("bob", "live");

        Message message = inbox.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals("live", message.text());
        subscription.close();
    }
}
