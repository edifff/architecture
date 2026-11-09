package ru.archlabs.chat.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Current presence and notification preferences of a user.
 */
public record UserState(String name, boolean online, boolean notificationsEnabled, Instant lastSeen) {
    public UserState {
        name = requireText(name, "Username");
        lastSeen = Objects.requireNonNull(lastSeen, "lastSeen");
    }

    public static UserState initial(String name, Instant at) {
        return new UserState(name, true, true, at);
    }

    public UserState withOnline(boolean online, Instant at) {
        return new UserState(name, online, notificationsEnabled, at);
    }

    public UserState withNotifications(boolean enabled) {
        return new UserState(name, online, enabled, lastSeen);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.strip();
    }
}
