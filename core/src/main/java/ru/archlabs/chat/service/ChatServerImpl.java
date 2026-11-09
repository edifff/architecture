package ru.archlabs.chat.service;

import ru.archlabs.chat.ChatServer;
import ru.archlabs.chat.ChatSession;
import ru.archlabs.chat.domain.ChatConflictException;
import ru.archlabs.chat.domain.ChatMessage;
import ru.archlabs.chat.domain.ChatValidationException;
import ru.archlabs.chat.domain.UserState;
import ru.archlabs.chat.metrics.ChatMetrics;
import ru.archlabs.chat.storage.ChatStorage;
import ru.archlabs.chat.storage.InMemoryChatStorage;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Coordinates the logical categories while keeping one lock around session state.
 */
public final class ChatServerImpl implements ChatServer {
    private final Object lock = new Object();
    private final SessionRegistry sessions = new SessionRegistry();
    private final ChatMetrics metrics;
    private final UserAccountService users;
    private final MessageService messages;
    private final NotificationService notifications;
    private final MessageDeliveryService delivery;

    public ChatServerImpl() {
        this(new InMemoryChatStorage());
    }

    public ChatServerImpl(ChatStorage storage) {
        this(storage, new ChatMetrics());
    }

    public ChatServerImpl(ChatStorage storage, ChatMetrics metrics) {
        this(storage, metrics, Clock.systemUTC());
    }

    public ChatServerImpl(ChatStorage storage, ChatMetrics metrics, Clock clock) {
        Objects.requireNonNull(storage, "storage");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.users = new UserAccountService(storage, clock);
        this.messages = new MessageService(storage, clock);
        this.notifications = new NotificationService(storage, clock);
        this.delivery = new MessageDeliveryService(users, notifications, metrics);
    }

    @Override
    public ChatSession connect(String userName) {
        var user = requireName(userName);
        metrics.requestProcessed("connect");
        synchronized (lock) {
            if (sessions.contains(user)) {
                throw new ChatConflictException("User is already connected: " + user);
            }
            var state = users.connect(user);
            var session = new ChatSessionImpl(this, user);
            sessions.add(session);
            metrics.onlineUsers(sessions.size());
            if (state.notificationsEnabled()) {
                notifications
                        .pending(user)
                        .forEach(pending -> session.deliver(pending.message(), pending.id()));
            }
            return session;
        }
    }

    @Override
    public UserState user(String userName) {
        metrics.requestProcessed("user");
        synchronized (lock) {
            return users.require(userName);
        }
    }

    @Override
    public List<ChatMessage> history(int limit) {
        metrics.requestProcessed("history");
        return messages.history(limit);
    }

    @Override
    public ChatMetrics metrics() {
        return metrics;
    }

    ChatMessage send(ChatSessionImpl session, String text) {
        metrics.requestProcessed("send");
        ChatMessage message;
        List<ChatSessionImpl> liveSessions;
        synchronized (lock) {
            requireActive(session);
            var offlineRecipients =
                    users.users().stream()
                            .filter(user -> !sessions.contains(user.name()))
                            .map(UserState::name)
                            .toList();
            message = messages.append(session.userName(), text, offlineRecipients);
            metrics.notificationStored(offlineRecipients.size());
            liveSessions = sessions.snapshot();
        }
        delivery.deliver(liveSessions, message);
        return message;
    }

    List<ChatMessage> history(ChatSessionImpl session, int limit) {
        metrics.requestProcessed("history");
        synchronized (lock) {
            requireActive(session);
            return messages.history(limit);
        }
    }

    void setNotificationsEnabled(ChatSessionImpl session, boolean enabled) {
        metrics.requestProcessed("set_notifications");
        synchronized (lock) {
            requireActive(session);
            users.setNotificationsEnabled(session.userName(), enabled);
            if (enabled) {
                notifications
                        .pending(session.userName())
                        .forEach(
                                pending ->
                                        session.deliver(pending.message(), pending.id()));
            } else {
                session.clearPendingNotifications();
            }
        }
    }

    void disconnect(ChatSessionImpl session) {
        metrics.requestProcessed("disconnect");
        synchronized (lock) {
            if (sessions.remove(session)) {
                users.disconnect(session.userName());
                metrics.onlineUsers(sessions.size());
            }
        }
    }

    void notificationDelivered(ChatSessionImpl session, long notificationId) {
        notifications.acknowledge(notificationId);
        metrics.notificationDelivered("backlog", 1);
    }

    @Override
    public void close() {
        synchronized (lock) {
            for (var session : sessions.snapshot()) {
                session.close();
            }
            sessions.clear();
            metrics.onlineUsers(0);
            users.close();
        }
    }

    private void requireActive(ChatSessionImpl session) {
        if (sessions.find(session.userName()).orElse(null) != session) {
            throw new ChatConflictException("Session is not active");
        }
    }

    private static String requireName(String value) {
        if (value == null || value.isBlank()) {
            throw new ChatValidationException("Username must not be blank");
        }
        return value.strip();
    }
}
