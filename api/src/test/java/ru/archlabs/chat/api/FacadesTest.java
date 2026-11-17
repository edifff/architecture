package ru.archlabs.chat.api;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ru.archlabs.chat.domain.ChatUser;
import ru.archlabs.chat.domain.ChatValidationException;
import ru.archlabs.chat.domain.Message;


final class FacadesTest {
    private FakeChatGateway gateway;
    private ChatFacades facades;

    @BeforeEach
    void setUp() {
        gateway = new FakeChatGateway();
        facades = ChatFacades.over(gateway);
    }

    @Test
    void messageFacadeParsesTrimsAndSends() {
        Message message = facades.messages().send("alice", "{\"text\":\"  hello  \"}");

        Assertions.assertEquals("alice", message.sender());
        Assertions.assertEquals("hello", gateway.lastText());
    }

    @Test
    void messageFacadeRejectsMalformedJson() {
        Assertions.assertThrows(
                ChatValidationException.class,
                () -> facades.messages().send("alice", "{not-json"));
    }

    @Test
    void messageFacadeRejectsBlankText() {
        Assertions.assertThrows(
                ChatValidationException.class,
                () -> facades.messages().send("alice", "{\"text\":\"   \"}"));
    }

    @Test
    void historyUsesDefaultAndBoundedLimits() {
        facades.messages().history("alice", null);
        Assertions.assertEquals(50, gateway.lastLimit());

        facades.messages().history("alice", "7");
        Assertions.assertEquals(7, gateway.lastLimit());

        facades.messages().history("alice", "5000");
        Assertions.assertEquals(1000, gateway.lastLimit());
    }

    @Test
    void historyRejectsInvalidLimit() {
        Assertions.assertThrows(
                ChatValidationException.class,
                () -> facades.messages().history("alice", "0"));
        Assertions.assertThrows(
                ChatValidationException.class,
                () -> facades.messages().history("alice", "many"));
    }

    @Test
    void notificationFacadeParsesBoolean() {
        facades.notifications().setEnabled("alice", "{\"enabled\":false}");

        Assertions.assertEquals("alice", gateway.lastUser());
        Assertions.assertFalse(gateway.lastEnabled());
    }

    @Test
    void notificationFacadeSubscribes() {
        AutoCloseable subscription = facades.notifications().subscribe("alice", ignored -> {
        });

        Assertions.assertNotNull(subscription);
        Assertions.assertNotNull(gateway.lastListener());
    }

    @Test
    void rejectsBlankUser() {
        Assertions.assertThrows(ChatValidationException.class, () -> facades.sessions().connect("  "));
    }

    @Test
    void connectReturnsUserState() {
        ChatUser user = facades.sessions().connect("alice");

        Assertions.assertEquals("alice", user.name());
        Assertions.assertTrue(user.online());
    }

    @Test
    void statusFacadeParsesTextAndDelegates() {
        StatusReport report = facades.status().broadcast("alice", "{\"text\":\"  status  \"}");

        Assertions.assertEquals("alice", gateway.lastUser());
        Assertions.assertEquals("status", gateway.lastStatusText());
        Assertions.assertEquals(1, report.chats());
    }

    @Test
    void statusFacadeRejectsBlankText() {
        Assertions.assertThrows(
                ChatValidationException.class,
                () -> facades.status().broadcast("alice", "{\"text\":\"   \"}"));
    }

    @Test
    void shardFacadeExposesAssignment() {
        ShardAssignment assignment = facades.shards().find("alice");

        Assertions.assertEquals("alice", assignment.user());
        Assertions.assertEquals("local", assignment.shard());
    }

    @Test
    void sendReturnsCreatedMessageRecord() {
        Message message = facades.messages().send("alice", "{\"text\":\"hello\"}");

        Assertions.assertEquals(1, message.id());
        Assertions.assertEquals("hello", message.text());
    }
}
