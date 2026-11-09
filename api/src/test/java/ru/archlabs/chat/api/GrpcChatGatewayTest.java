package ru.archlabs.chat.api;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.archlabs.chat.chat.ChatGrpcService;
import ru.archlabs.chat.domain.ChatMessage;
import ru.archlabs.chat.domain.ChatNotFoundException;
import ru.archlabs.chat.service.ChatServerImpl;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GrpcChatGatewayTest {
    private ChatServerImpl domain;
    private Server server;
    private ManagedChannel channel;
    private GrpcChatGateway gateway;

    @BeforeEach
    void setUp() throws Exception {
        var name = InProcessServerBuilder.generateName();
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
        assertTrue(gateway.connect("alice").online());

        var message = gateway.send("alice", "hello");
        assertEquals("hello", message.text());
        assertEquals(1, gateway.history("alice", 10).size());

        gateway.setNotificationsEnabled("alice", false);
        assertFalse(gateway.user("alice").notificationsEnabled());

        gateway.disconnect("alice");
        assertFalse(gateway.user("alice").online());
    }

    @Test
    void mapsGrpcNotFoundToDomainException() {
        assertThrows(ChatNotFoundException.class, () -> gateway.user("ghost"));
    }

    @Test
    void subscribesToLiveMessages() throws Exception {
        gateway.connect("alice");
        gateway.connect("bob");
        var inbox = new LinkedBlockingQueue<ChatMessage>();

        var subscription = gateway.subscribe("alice", inbox::add);
        gateway.send("bob", "live");

        var message = inbox.poll(5, TimeUnit.SECONDS);
        assertNotNull(message);
        assertEquals("live", message.text());
        subscription.close();
    }
}
