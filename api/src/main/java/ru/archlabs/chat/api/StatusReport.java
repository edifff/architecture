package ru.archlabs.chat.api;

import java.util.List;

import ru.archlabs.chat.domain.Message;

/**
 * Aggregated result of a status message sent to every chat in the system.
 */
public record StatusReport(
        String sender,
        Message message,
        int shardCount,
        int chats,
        int delivered,
        int queued,
        List<ShardReport> shards) {

    public StatusReport {
        shards = List.copyOf(shards);
    }
}
