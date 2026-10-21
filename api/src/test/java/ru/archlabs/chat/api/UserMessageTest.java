package ru.archlabs.chat.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class UserMessageTest {
    @Test
    void parsesConnect() {
        var message = UserMessage.parse("CONNECT ivan");

        assertEquals(Operation.CONNECT, message.operation());
        assertEquals("ivan", message.username());
        assertNull(message.text());
        assertNull(message.enabled());
    }

    @Test
    void parsesSendWithSpacesInText() {
        var message = UserMessage.parse("send ivan hello big world");

        assertEquals(Operation.SEND, message.operation());
        assertEquals("ivan", message.username());
        assertEquals("hello big world", message.text());
    }

    @Test
    void parsesNotificationsFlag() {
        var message = UserMessage.parse("SET_NOTIFICATIONS ivan false");

        assertEquals(Operation.SET_NOTIFICATIONS, message.operation());
        assertEquals(Boolean.FALSE, message.enabled());
    }

    @Test
    void rejectsUnknownOperation() {
        var exception = assertThrows(IllegalArgumentException.class, () -> UserMessage.parse("FLY ivan"));
        assertTrue(exception.getMessage().contains("Unknown operation"));
    }

    @Test
    void rejectsMissingUsername() {
        assertThrows(IllegalArgumentException.class, () -> UserMessage.parse("CONNECT"));
    }

    @Test
    void rejectsBlankMessage() {
        assertThrows(IllegalArgumentException.class, () -> UserMessage.parse("   "));
    }

    @Test
    void rejectsNotificationsWithoutFlag() {
        assertThrows(
                IllegalArgumentException.class, () -> UserMessage.parse("SET_NOTIFICATIONS ivan"));
    }
}
