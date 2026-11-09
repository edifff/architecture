package ru.archlabs.chat.api;

import ru.archlabs.chat.ChatServer;
import ru.archlabs.chat.ChatSession;
import ru.archlabs.chat.domain.ChatMessage;
import ru.archlabs.chat.domain.ChatNotFoundException;
import ru.archlabs.chat.domain.UserState;
import ru.archlabs.chat.metrics.ChatMetrics;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Lab 3 gateway: calls the in-process domain directly.
 */
public final class CoreChatGateway implements ChatGateway {
    private final ChatServer server;
    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();

    public CoreChatGateway(ChatServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public UserState connect(String user) {
        var session = server.connect(user);
        sessions.put(session.userName(), session);
        return server.user(session.userName());
    }

    @Override
    public void disconnect(String user) {
        var session = sessions.remove(user);
        if (session == null) {
            throw new ChatNotFoundException("User is not connected: " + user);
        }
        session.close();
    }

    @Override
    public ChatMessage send(String user, String text) {
        return session(user).send(text);
    }

    @Override
    public List<ChatMessage> history(String user, int limit) {
        return session(user).history(limit);
    }

    @Override
    public void setNotificationsEnabled(String user, boolean enabled) {
        session(user).setNotificationsEnabled(enabled);
    }

    @Override
    public UserState user(String user) {
        return server.user(user);
    }

    @Override
    public AutoCloseable subscribe(String user, Consumer<ChatMessage> listener) {
        return session(user).addListener(listener);
    }

    @Override
    public ChatMetrics metrics() {
        return server.metrics();
    }

    @Override
    public void close() {
        sessions.values().forEach(ChatSession::close);
        sessions.clear();
        server.close();
    }

    private ChatSession session(String user) {
        var session = sessions.get(user);
        if (session == null) {
            throw new ChatNotFoundException("User is not connected: " + user);
        }
        return session;
    }
}
