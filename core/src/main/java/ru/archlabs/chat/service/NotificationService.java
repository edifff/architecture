package ru.archlabs.chat.service;

import ru.archlabs.chat.domain.ChatMessage;
import ru.archlabs.chat.domain.ChatNotification;
import ru.archlabs.chat.storage.ChatStorage;

import java.time.Clock;
import java.util.List;

/**
 * Logical category: notification outbox and delivery acknowledgement.
 */
final class NotificationService {
    private final ChatStorage storage;
    private final Clock clock;

    NotificationService(ChatStorage storage, Clock clock) {
        this.storage = storage;
        this.clock = clock;
    }

    List<ChatNotification> pending(String recipient) {
        return storage.pendingNotifications(recipient);
    }

    ChatNotification enqueue(String recipient, ChatMessage message) {
        return storage.enqueueNotification(recipient, message, clock.instant());
    }

    void acknowledge(long notificationId) {
        storage.markNotificationDelivered(notificationId, clock.instant());
    }
}
