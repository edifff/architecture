package ru.archlabs.chat.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.archlabs.chat.domain.ChatMessage;
import ru.archlabs.chat.domain.ChatValidationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        var message = facades.messages().send("alice", "{\"text\":\"  hello  \"}");

        assertEquals("alice", message.sender());
        assertEquals("hello", gateway.lastText);
    }

    @Test
    void messageFacadeRejectsMalformedJson() {
        assertThrows(
                ChatValidationException.class,
                () -> facades.messages().send("alice", "{not-json"));
    }

    @Test
    void messageFacadeRejectsBlankText() {
        assertThrows(
                ChatValidationException.class,
                () -> facades.messages().send("alice", "{\"text\":\"   \"}"));
    }

    @Test
    void historyUsesDefaultAndBoundedLimits() {
        facades.messages().history("alice", null);
        assertEquals(50, gateway.lastLimit);

        facades.messages().history("alice", "7");
        assertEquals(7, gateway.lastLimit);

        facades.messages().history("alice", "5000");
        assertEquals(1000, gateway.lastLimit);
    }

    @Test
    void historyRejectsInvalidLimit() {
        assertThrows(
                ChatValidationException.class,
                () -> facades.messages().history("alice", "0"));
        assertThrows(
                ChatValidationException.class,
                () -> facades.messages().history("alice", "many"));
    }

    @Test
    void notificationFacadeParsesBoolean() {
        facades.notifications().setEnabled("alice", "{\"enabled\":false}");

        assertEquals("alice", gateway.lastUser);
        assertFalse(gateway.lastEnabled);
    }

    @Test
    void notificationFacadeSubscribes() {
        var subscription = facades.notifications().subscribe("alice", ignored -> {
        });

        assertNotNull(subscription);
        assertNotNull(gateway.lastListener);
    }

    @Test
    void rejectsBlankUser() {
        assertThrows(ChatValidationException.class, () -> facades.sessions().connect("  "));
    }

    @Test
    void connectReturnsUserState() {
        var user = facades.sessions().connect("alice");

        assertEquals("alice", user.name());
        assertTrue(user.online());
    }

    @Test
    void sendReturnsCreatedMessageRecord() {
        ChatMessage message = facades.messages().send("alice", "{\"text\":\"hello\"}");

        assertEquals(1, message.id());
        assertEquals("hello", message.text());
    }
}
