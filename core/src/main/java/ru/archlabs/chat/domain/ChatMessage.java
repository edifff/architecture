package ru.archlabs.chat.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A single chat message. Messages are immutable and always have a storage id.
 */
public record ChatMessage(long id, String sender, String text, Instant createdAt) {
    public ChatMessage {
        if (id <= 0) {
            throw new IllegalArgumentException("Message id must be positive");
        }
        sender = requireText(sender, "Sender");
        text = requireText(text, "Message");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.strip();
    }
}
