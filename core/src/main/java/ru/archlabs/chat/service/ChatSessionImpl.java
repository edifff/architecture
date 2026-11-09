package ru.archlabs.chat.service;

import ru.archlabs.chat.ChatSession;
import ru.archlabs.chat.domain.ChatConflictException;
import ru.archlabs.chat.domain.ChatMessage;

import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Logical category: one user's live session, listener fan-out, and local backlog.
 */
final class ChatSessionImpl implements ChatSession {
    private record Delivery(ChatMessage message, Long notificationId) {
    }

    private final ChatServerImpl server;
    private final String userName;
    private final List<Consumer<ChatMessage>> listeners = new CopyOnWriteArrayList<>();
    private final Queue<Delivery> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    ChatSessionImpl(ChatServerImpl server, String userName) {
        this.server = server;
        this.userName = userName;
    }

    @Override
    public String userName() {
        return userName;
    }

    @Override
    public ChatMessage send(String text) {
        ensureOpen();
        return server.send(this, text);
    }

    @Override
    public List<ChatMessage> history(int limit) {
        ensureOpen();
        return server.history(this, limit);
    }

    @Override
    public void setNotificationsEnabled(boolean enabled) {
        ensureOpen();
        server.setNotificationsEnabled(this, enabled);
    }

    @Override
    public AutoCloseable addListener(Consumer<ChatMessage> listener) {
        ensureOpen();
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        drain();
        return () -> listeners.remove(listener);
    }

    /**
     * Accepts a message for this session. A notification id means the message must be
     * acknowledged only after a listener has consumed it.
     */
    boolean deliver(ChatMessage message, Long notificationId) {
        if (closed.get()) {
            return false;
        }
        var delivery = new Delivery(message, notificationId);
        if (listeners.isEmpty() || !dispatch(delivery)) {
            pending.add(delivery);
        }
        return true;
    }

    void clearPendingNotifications() {
        pending.removeIf(delivery -> delivery.notificationId() != null);
    }

    private void drain() {
        while (!closed.get()) {
            var delivery = pending.poll();
            if (delivery == null) {
                return;
            }
            if (!dispatch(delivery)) {
                pending.add(delivery);
                return;
            }
        }
    }

    private boolean dispatch(Delivery delivery) {
        var current = List.copyOf(listeners);
        if (current.isEmpty()) {
            return false;
        }
        for (var listener : current) {
            try {
                listener.accept(delivery.message());
            } catch (RuntimeException exception) {
                return false;
            }
        }
        if (delivery.notificationId() != null) {
            server.notificationDelivered(this, delivery.notificationId());
        }
        return true;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            server.disconnect(this);
            listeners.clear();
            pending.clear();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new ChatConflictException("Session is not active");
        }
    }
}
