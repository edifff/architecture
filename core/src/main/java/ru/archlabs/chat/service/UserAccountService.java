package ru.archlabs.chat.service;

import java.time.Clock;
import java.util.List;

import ru.archlabs.chat.domain.ChatNotFoundException;
import ru.archlabs.chat.domain.ChatUser;
import ru.archlabs.chat.domain.ChatValidationException;
import ru.archlabs.chat.storage.ChatStorage;

/**
 * Logical category: user accounts, presence, and preferences.
 */
final class UserAccountService {
    private final ChatStorage storage;
    private final Clock clock;

    UserAccountService(ChatStorage storage, Clock clock) {
        this.storage = storage;
        this.clock = clock;
        storage.initialize();
        storage.resetOnlineUsers();
    }

    ChatUser connect(String name) {
        String user = requireName(name);
        storage.connect(user, clock.instant());
        return require(user);
    }

    ChatUser disconnect(String name) {
        String user = requireName(name);
        storage.disconnect(user, clock.instant());
        return require(user);
    }

    ChatUser require(String name) {
        String user = requireName(name);
        return storage
                .findUser(user)
                .orElseThrow(() -> new ChatNotFoundException("Unknown user: " + user));
    }

    List<ChatUser> users() {
        return storage.allUsers();
    }

    void setNotificationsEnabled(String name, boolean enabled) {
        String user = requireName(name);
        storage.setNotificationsEnabled(user, enabled);
    }

    boolean notificationsEnabled(String name) {
        return storage
                .findUser(requireName(name))
                .map(ChatUser::notificationsEnabled)
                .orElse(false);
    }

    void close() {
        storage.close();
    }

    private static String requireName(String value) {
        if (value == null || value.isBlank()) {
            throw new ChatValidationException("Username must not be blank");
        }
        return value.strip();
    }
}
