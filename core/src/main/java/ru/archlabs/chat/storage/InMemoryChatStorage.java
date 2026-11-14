package ru.archlabs.chat.storage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import ru.archlabs.chat.domain.ChatNotFoundException;
import ru.archlabs.chat.domain.ChatUser;
import ru.archlabs.chat.domain.ChatValidationException;
import ru.archlabs.chat.domain.Message;
import ru.archlabs.chat.domain.Notification;

/**
 * Thread-safe RAM storage used by lab 1.
 */
public final class InMemoryChatStorage implements ChatStorage {
    private static final String AT = "at";

    private final Map<String, ChatUser> users = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Message> messages = new CopyOnWriteArrayList<>();
    private final Map<String, CopyOnWriteArrayList<Notification>> notifications =
            new ConcurrentHashMap<>();
    private final AtomicLong messageIds = new AtomicLong();
    private final AtomicLong notificationIds = new AtomicLong();

    @Override
    public void initialize() {
    }

    @Override
    public void connect(String name, Instant at) {
        String user = requireName(name);
        Objects.requireNonNull(at, AT);
        users.compute(
                user,
                (ignored, previous) ->
                        previous == null
                                ? ChatUser.initial(user, at)
                                : previous.withOnline(true, at));
    }

    @Override
    public void disconnect(String name, Instant at) {
        String user = requireName(name);
        Objects.requireNonNull(at, AT);
        users.computeIfPresent(user, (ignored, previous) -> previous.withOnline(false, at));
    }

    @Override
    public void setNotificationsEnabled(String name, boolean enabled) {
        String user = requireName(name);
        AtomicBoolean updated = new AtomicBoolean();
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
    public Optional<ChatUser> findUser(String name) {
        return Optional.ofNullable(users.get(requireName(name)));
    }

    @Override
    public List<ChatUser> allUsers() {
        return List.copyOf(users.values());
    }

    @Override
    public Message appendMessage(
            String sender, String text, List<String> notificationRecipients, Instant at) {
        String author = requireName(sender);
        String body = requireText(text);
        Objects.requireNonNull(notificationRecipients, "notificationRecipients");
        Objects.requireNonNull(at, AT);

        Message message;
        synchronized (this) {
            message = new Message(messageIds.incrementAndGet(), author, body, at);
            messages.add(message);
            for (String recipient : notificationRecipients) {
                enqueueNotification(recipient, message, at);
            }
        }
        return message;
    }

    @Override
    public List<Message> history(int limit) {
        if (limit <= 0) {
            throw new ChatValidationException("History limit must be positive");
        }
        List<Message> snapshot = List.copyOf(messages);
        int from = Math.max(0, snapshot.size() - limit);
        return List.copyOf(snapshot.subList(from, snapshot.size()));
    }

    @Override
    public Notification enqueueNotification(String recipient, Message message, Instant at) {
        String target = requireName(recipient);
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(at, AT);
        Notification notification =
                new Notification(notificationIds.incrementAndGet(), target, message, at);
        notifications
                .computeIfAbsent(target, ignored -> new CopyOnWriteArrayList<>())
                .add(notification);
        return notification;
    }

    @Override
    public List<Notification> pendingNotifications(String recipient) {
        return List.copyOf(
                notifications.getOrDefault(requireName(recipient), new CopyOnWriteArrayList<>()));
    }

    @Override
    public void markNotificationDelivered(long notificationId, Instant at) {
        Objects.requireNonNull(at, AT);
        for (CopyOnWriteArrayList<Notification> queue : notifications.values()) {
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
