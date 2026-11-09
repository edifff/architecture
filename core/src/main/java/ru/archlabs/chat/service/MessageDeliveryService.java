package ru.archlabs.chat.service;

import ru.archlabs.chat.domain.ChatMessage;
import ru.archlabs.chat.metrics.ChatMetrics;

import java.util.List;

/**
 * Logical category: fan-out of a stored message to live sessions.
 */
final class MessageDeliveryService {
    private final UserAccountService users;
    private final NotificationService notifications;
    private final ChatMetrics metrics;

    MessageDeliveryService(
            UserAccountService users, NotificationService notifications, ChatMetrics metrics) {
        this.users = users;
        this.notifications = notifications;
        this.metrics = metrics;
    }

    int deliver(List<ChatSessionImpl> recipients, ChatMessage message) {
        var delivered = 0;
        for (var recipient : recipients) {
            if (!users.notificationsEnabled(recipient.userName())) {
                continue;
            }
            if (recipient.deliver(message, null)) {
                delivered++;
            } else {
                notifications.enqueue(recipient.userName(), message);
                metrics.notificationStored(1);
            }
        }
        metrics.notificationDelivered("live", delivered);
        return delivered;
    }
}
