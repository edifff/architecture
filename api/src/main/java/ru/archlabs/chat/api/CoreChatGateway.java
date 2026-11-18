package ru.archlabs.chat.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import ru.archlabs.chat.ChatServer;
import ru.archlabs.chat.ChatSession;
import ru.archlabs.chat.DeliverySummary;
import ru.archlabs.chat.StatusDispatch;
import ru.archlabs.chat.domain.ChatNotFoundException;
import ru.archlabs.chat.domain.ChatUser;
import ru.archlabs.chat.domain.Message;
import ru.archlabs.chat.metrics.ChatMetrics;

/**
 *  calls the in-process domain directly.
 */
public final class CoreChatGateway implements ChatGateway {
    private static final String NOT_CONNECTED = "User is not connected: ";
    private static final String LOCAL_SHARD = "local";

    private final ChatServer server;
    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();

    public CoreChatGateway(ChatServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public ChatUser connect(String user) {
        ChatSession session = server.connect(user);
        sessions.put(session.userName(), session);
        return server.user(session.userName());
    }

    @Override
    public void disconnect(String user) {
        ChatSession session = sessions.remove(user);
        if (session == null) {
            throw new ChatNotFoundException(NOT_CONNECTED + user);
        }
        session.close();
    }

    @Override
    public Message send(String user, String text) {
        return session(user).send(text);
    }

    @Override
    public List<Message> history(String user, int limit) {
        return session(user).history(limit);
    }

    @Override
    public void setNotificationsEnabled(String user, boolean enabled) {
        session(user).setNotificationsEnabled(enabled);
    }

    @Override
    public ChatUser user(String user) {
        return server.user(user);
    }

    @Override
    public AutoCloseable subscribe(String user, Consumer<Message> listener) {
        return session(user).addListener(listener);
    }

    @Override
    public StatusReport broadcastStatus(String user, String text) {
        StatusDispatch dispatch = session(user).broadcastStatus(text);
        DeliverySummary summary = dispatch.summary();
        ShardReport report =
                new ShardReport(
                        LOCAL_SHARD, summary.chats(), summary.delivered(), summary.queued());
        return new StatusReport(
                user,
                dispatch.message(),
                1,
                summary.chats(),
                summary.delivered(),
                summary.queued(),
                List.of(report));
    }

    @Override
    public ShardAssignment shardAssignment(String user) {
        return new ShardAssignment(user, LOCAL_SHARD, 1);
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
        ChatSession session = sessions.get(user);
        if (session == null) {
            throw new ChatNotFoundException(NOT_CONNECTED + user);
        }
        return session;
    }
}
