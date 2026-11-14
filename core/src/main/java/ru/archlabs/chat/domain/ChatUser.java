package ru.archlabs.chat.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Current presence and notification preferences of a user.
 */
public record ChatUser(String name, boolean online, boolean notificationsEnabled, Instant lastSeen) {
    public ChatUser {
        name = requireText(name, "Username");
        lastSeen = Objects.requireNonNull(lastSeen, "lastSeen");
    }

    public static ChatUser initial(String name, Instant at) {
        return new ChatUser(name, true, true, at);
    }

    public ChatUser withOnline(boolean online, Instant at) {
        return new ChatUser(name, online, notificationsEnabled, at);
    }

    public ChatUser withNotifications(boolean enabled) {
        return new ChatUser(name, online, enabled, lastSeen);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.strip();
    }
}
