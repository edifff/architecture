package ru.archlabs.chat.service;

import java.time.Clock;
import java.util.List;

import ru.archlabs.chat.domain.Message;
import ru.archlabs.chat.domain.Notification;
import ru.archlabs.chat.storage.ChatStorage;

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

    List<Notification> pending(String recipient) {
        return storage.pendingNotifications(recipient);
    }

    Notification enqueue(String recipient, Message message) {
        return storage.enqueueNotification(recipient, message, clock.instant());
    }

    void acknowledge(long notificationId) {
        storage.markNotificationDelivered(notificationId, clock.instant());
    }
}
