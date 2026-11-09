package ru.archlabs.chat.api;

import ru.archlabs.chat.domain.ChatMessage;
import ru.archlabs.chat.domain.UserState;
import ru.archlabs.chat.metrics.ChatMetrics;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

final class FakeChatGateway implements ChatGateway {
    String lastUser;
    String lastText;
    boolean lastEnabled;
    int lastLimit;
    Consumer<ChatMessage> lastListener;
    UserState lastState;

    @Override
    public UserState connect(String user) {
        lastUser = user;
        lastState = new UserState(user, true, true, Instant.EPOCH);
        return lastState;
    }

    @Override
    public void disconnect(String user) {
        lastUser = user;
    }

    @Override
    public ChatMessage send(String user, String text) {
        lastUser = user;
        lastText = text;
        return new ChatMessage(1, user, text, Instant.EPOCH);
    }

    @Override
    public List<ChatMessage> history(String user, int limit) {
        lastUser = user;
        lastLimit = limit;
        return List.of(new ChatMessage(1, user, "hello", Instant.EPOCH));
    }

    @Override
    public void setNotificationsEnabled(String user, boolean enabled) {
        lastUser = user;
        lastEnabled = enabled;
    }

    @Override
    public UserState user(String user) {
        lastUser = user;
        return new UserState(user, true, true, Instant.EPOCH);
    }

    @Override
    public AutoCloseable subscribe(String user, Consumer<ChatMessage> listener) {
        lastUser = user;
        lastListener = listener;
        return () -> {
        };
    }

    @Override
    public ChatMetrics metrics() {
        return new ChatMetrics();
    }

    @Override
    public void close() {
    }
}
