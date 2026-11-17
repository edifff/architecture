package ru.archlabs.chat;

/**
 * Per-shard result of applying one message to every local chat entity.
 */
public record DeliverySummary(int chats, int delivered, int queued) {
    public DeliverySummary {
        if (chats < 0 || delivered < 0 || queued < 0) {
            throw new IllegalArgumentException("Delivery counters must not be negative");
        }
    }
}
