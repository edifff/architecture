package ru.archlabs.chat.storage;

import ru.archlabs.chat.domain.ChatMessage;
import ru.archlabs.chat.domain.ChatNotification;
import ru.archlabs.chat.domain.ChatNotFoundException;
import ru.archlabs.chat.domain.ChatValidationException;
import ru.archlabs.chat.domain.UserState;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe RAM storage used by lab 1.
 */
public final class InMemoryChatStorage implements ChatStorage {
    private final Map<String, UserState> users = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<ChatMessage> messages = new CopyOnWriteArrayList<>();
    private final Map<String, CopyOnWriteArrayList<ChatNotification>> notifications =
            new ConcurrentHashMap<>();
    private final AtomicLong messageIds = new AtomicLong();
    private final AtomicLong notificationIds = new AtomicLong();

    @Override
    public void initialize() {
    }

    @Override
    public void connect(String name, Instant at) {
        var user = requireName(name);
        Objects.requireNonNull(at, "at");
        users.compute(
                user,
                (ignored, previous) ->
                        previous == null
                                ? UserState.initial(user, at)
                                : previous.withOnline(true, at));
    }

    @Override
    public void disconnect(String name, Instant at) {
        var user = requireName(name);
        Objects.requireNonNull(at, "at");
        users.computeIfPresent(user, (ignored, previous) -> previous.withOnline(false, at));
    }

    @Override
    public void setNotificationsEnabled(String name, boolean enabled) {
        var user = requireName(name);
        var updated = new java.util.concurrent.atomic.AtomicBoolean();
        users.computeIfPresent(
                user,
                (ignored, previous) -> {
                    updated.set(true);
                    return previous.withNotifications(enabled);
                });
        if (!updated.get()) {
            throw new ChatNotFoundException("Unknown user: " + user);
        }
    }

    @Override
    public Optional<UserState> findUser(String name) {
        return Optional.ofNullable(users.get(requireName(name)));
    }

    @Override
    public List<UserState> allUsers() {
        return List.copyOf(users.values());
    }

    @Override
    public ChatMessage appendMessage(
            String sender, String text, List<String> notificationRecipients, Instant at) {
        var author = requireName(sender);
        var body = requireText(text);
        Objects.requireNonNull(notificationRecipients, "notificationRecipients");
        Objects.requireNonNull(at, "at");

        ChatMessage message;
        synchronized (this) {
            message = new ChatMessage(messageIds.incrementAndGet(), author, body, at);
            messages.add(message);
            for (var recipient : notificationRecipients) {
                enqueueNotification(recipient, message, at);
            }
        }
        return message;
    }

    @Override
    public List<ChatMessage> history(int limit) {
        if (limit <= 0) {
            throw new ChatValidationException("History limit must be positive");
        }
        var snapshot = List.copyOf(messages);
        var from = Math.max(0, snapshot.size() - limit);
        return List.copyOf(snapshot.subList(from, snapshot.size()));
    }

    @Override
    public ChatNotification enqueueNotification(String recipient, ChatMessage message, Instant at) {
        var target = requireName(recipient);
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(at, "at");
        var notification =
                new ChatNotification(notificationIds.incrementAndGet(), target, message, at);
        notifications
                .computeIfAbsent(target, ignored -> new CopyOnWriteArrayList<>())
                .add(notification);
        return notification;
    }

    @Override
    public List<ChatNotification> pendingNotifications(String recipient) {
        return List.copyOf(
                notifications.getOrDefault(requireName(recipient), new CopyOnWriteArrayList<>()));
    }

    @Override
    public void markNotificationDelivered(long notificationId, Instant at) {
        Objects.requireNonNull(at, "at");
        for (var queue : notifications.values()) {
            queue.removeIf(notification -> notification.id() == notificationId);
        }
    }

    @Override
    public void resetOnlineUsers() {
        users.replaceAll((ignored, user) -> user.withOnline(false, user.lastSeen()));
    }

    private static String requireName(String value) {
        if (value == null || value.isBlank()) {
            throw new ChatValidationException("Username must not be blank");
        }
        return value.strip();
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new ChatValidationException("Message must not be blank");
        }
        return value.strip();
    }
}
