package ru.archlabs.chat.service;

import ru.archlabs.chat.domain.ChatNotFoundException;
import ru.archlabs.chat.domain.ChatValidationException;
import ru.archlabs.chat.domain.UserState;
import ru.archlabs.chat.storage.ChatStorage;

import java.time.Clock;
import java.util.List;

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

    UserState connect(String name) {
        var user = requireName(name);
        storage.connect(user, clock.instant());
        return require(user);
    }

    UserState disconnect(String name) {
        var user = requireName(name);
        storage.disconnect(user, clock.instant());
        return require(user);
    }

    UserState require(String name) {
        var user = requireName(name);
        return storage
                .findUser(user)
                .orElseThrow(() -> new ChatNotFoundException("Unknown user: " + user));
    }

    List<UserState> users() {
        return storage.allUsers();
    }

    void setNotificationsEnabled(String name, boolean enabled) {
        var user = requireName(name);
        storage.setNotificationsEnabled(user, enabled);
    }

    boolean notificationsEnabled(String name) {
        return storage
                .findUser(requireName(name))
                .map(UserState::notificationsEnabled)
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
