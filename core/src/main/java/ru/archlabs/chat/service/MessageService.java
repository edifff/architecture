package ru.archlabs.chat.service;

import java.time.Clock;
import java.util.List;

import ru.archlabs.chat.domain.ChatValidationException;
import ru.archlabs.chat.domain.Message;
import ru.archlabs.chat.storage.ChatStorage;

/**
 * Logical category: message creation and history.
 */
final class MessageService {
    private final ChatStorage storage;
    private final Clock clock;

    MessageService(ChatStorage storage, Clock clock) {
        this.storage = storage;
        this.clock = clock;
    }

    Message append(String sender, String text, List<String> notificationRecipients) {
        String body = requireText(text);
        return storage.appendMessage(sender, body, notificationRecipients, clock.instant());
    }

    List<Message> history(int limit) {
        if (limit <= 0) {
            throw new ChatValidationException("History limit must be positive");
        }
        return storage.history(limit);
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new ChatValidationException("Message must not be blank");
        }
        return value.strip();
    }
}
