package ru.archlabs.chat;

import ru.archlabs.chat.storage.ChatStorage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemoryChatStorage implements ChatStorage {
    private final Map<String, UserState> users = new LinkedHashMap<>();
    private final List<Message> messages = new ArrayList<>();
    private final Map<String, List<Long>> notifications = new LinkedHashMap<>();
    private long nextId = 1;

    @Override
    public synchronized void connect(String name, Instant at) {
        var existing = users.get(name);
        if (existing != null && existing.online()) {
            throw new IllegalStateException("User is already online: " + name);
        }
        users.put(name, new UserState(name, true, existing == null ? at : existing.lastConnected(), existing == null || existing.notificationsEnabled()));
    }

    @Override
    public synchronized void disconnect(String name, Instant at) {
        var existing = requireUser(name);
        users.put(name, new UserState(name, false, at, existing.notificationsEnabled()));
    }

    @Override
    public synchronized void setNotificationsEnabled(String name, boolean enabled) {
        var existing = requireUser(name);
        users.put(name, new UserState(name, existing.online(), existing.lastConnected(), enabled));
    }

    @Override
    public synchronized Message append(String sender, String text, Instant sentAt) {
        var message = new Message(nextId++, sender, text, sentAt);
        messages.add(message);
        users.values().stream()
                .filter(user -> !user.online() && user.notificationsEnabled())
                .forEach(
                        user ->
                                notifications
                                        .computeIfAbsent(user.name(), ignored -> new ArrayList<>())
                                        .add(message.id()));
        return message;
    }

    @Override
    public synchronized List<Message> undeliveredNotifications(String recipient) {
        return notifications.getOrDefault(recipient, List.of()).stream()
                .map(id -> messages.stream().filter(message -> message.id() == id).findFirst().orElseThrow())
                .toList();
    }

    @Override
    public synchronized void clearNotifications(String recipient, List<Long> messageIds) {
        var pending = notifications.get(recipient);
        if (pending != null) {
            pending.removeAll(messageIds);
        }
    }

    @Override
    public synchronized List<Message> history() {
        return List.copyOf(messages);
    }

    @Override
    public synchronized Optional<UserState> user(String name) {
        return Optional.ofNullable(users.get(name));
    }

    @Override
    public synchronized List<UserState> onlineUsersWithNotificationsEnabled() {
        return users.values().stream()
                .filter(user -> user.online() && user.notificationsEnabled())
                .toList();
    }

    @Override
    public synchronized void resetOnlineUsers() {
        users.replaceAll((name, user) -> new UserState(name, false, user.lastConnected(), user.notificationsEnabled()));
    }

    private UserState requireUser(String name) {
        var user = users.get(name);
        if (user == null) {
            throw new IllegalArgumentException("Unknown user: " + name);
        }
        return user;
    }
}
