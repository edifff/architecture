package ru.archlabs.chat.api;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import ru.archlabs.chat.domain.ChatUser;
import ru.archlabs.chat.domain.Message;
import ru.archlabs.chat.metrics.ChatMetrics;

final class FakeChatGateway implements ChatGateway {
    private String lastUser;
    private String lastText;
    private String lastStatusText;
    private boolean lastEnabled;
    private int lastLimit;
    private Consumer<Message> lastListener;
    private ChatUser lastState;

    String lastUser() {
        return lastUser;
    }

    String lastText() {
        return lastText;
    }

    String lastStatusText() {
        return lastStatusText;
    }

    boolean lastEnabled() {
        return lastEnabled;
    }

    int lastLimit() {
        return lastLimit;
    }

    Consumer<Message> lastListener() {
        return lastListener;
    }

    @Override
    public ChatUser connect(String user) {
        lastUser = user;
        lastState = new ChatUser(user, true, true, Instant.EPOCH);
        return lastState;
    }

    @Override
    public void disconnect(String user) {
        lastUser = user;
    }

    @Override
    public Message send(String user, String text) {
        lastUser = user;
        lastText = text;
        return new Message(1, user, text, Instant.EPOCH);
    }

    @Override
    public List<Message> history(String user, int limit) {
        lastUser = user;
        lastLimit = limit;
        return List.of(new Message(1, user, "hello", Instant.EPOCH));
    }

    @Override
    public void setNotificationsEnabled(String user, boolean enabled) {
        lastUser = user;
        lastEnabled = enabled;
    }

    @Override
    public ChatUser user(String user) {
        lastUser = user;
        return new ChatUser(user, true, true, Instant.EPOCH);
    }

    @Override
    public AutoCloseable subscribe(String user, Consumer<Message> listener) {
        lastUser = user;
        lastListener = listener;
        return () -> {
        };
    }

    @Override
    public StatusReport broadcastStatus(String user, String text) {
        lastUser = user;
        lastStatusText = text;
        return new StatusReport(
                user,
                new Message(1, user, text, Instant.EPOCH),
                1,
                1,
                1,
                0,
                List.of(new ShardReport("local", 1, 1, 0)));
    }

    @Override
    public ShardAssignment shardAssignment(String user) {
        lastUser = user;
        return new ShardAssignment(user, "local", 1);
    }

    @Override
    public ChatMetrics metrics() {
        return new ChatMetrics();
    }

    @Override
    public void close() {
    }
}
