package ru.archlabs.chat.api;

/**
 * One shard's contribution to a general operation.
 */
public record ShardReport(String shard, int chats, int delivered, int queued) {
}
