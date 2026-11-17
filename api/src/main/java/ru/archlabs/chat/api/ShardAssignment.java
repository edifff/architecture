package ru.archlabs.chat.api;

/**
 * The shard selected for one business entity key.
 */
public record ShardAssignment(String user, String shard, int shardCount) {
}
