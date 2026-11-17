package ru.archlabs.chat.api;

import ru.archlabs.chat.domain.ChatValidationException;

/**
 * Deterministic mapping from a business entity key to an identical shard.
 * FNV-1a is stable across JVMs and container restarts, unlike object hash codes.
 */
public final class ShardRouter {
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private final int shardCount;

    public ShardRouter(int shardCount) {
        if (shardCount < 1) {
            throw new IllegalArgumentException("Shard count must be positive");
        }
        this.shardCount = shardCount;
    }

    public int indexOf(String entity) {
        if (entity == null || entity.isBlank()) {
            throw new ChatValidationException("Shard key must not be blank");
        }
        long hash = FNV_OFFSET_BASIS;
        String key = entity.strip();
        for (int index = 0; index < key.length(); index++) {
            hash ^= key.charAt(index);
            hash *= FNV_PRIME;
        }
        return (int) Long.remainderUnsigned(hash, shardCount);
    }

    public int shardCount() {
        return shardCount;
    }
}
