package ru.archlabs.chat.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A notification that has not necessarily been delivered yet.
 */
public record Notification(long id, String recipient, Message message, Instant createdAt) {
    public Notification {
        if (id <= 0) {
            throw new IllegalArgumentException("Notification id must be positive");
        }
        recipient = requireText(recipient, "Recipient");
        message = Objects.requireNonNull(message, "message");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.strip();
    }
}
