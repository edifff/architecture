package ru.archlabs.chat.api;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

final class ShardSpecTest {
    @Test
    void parsesHostPortEntriesWithDefaultIds() {
        ShardedChatGateway gateway =
                ShardedChatGateway.fromSpec("chat-1:9091,chat-2:9091,chat-3:9091");
        try {
            Assertions.assertEquals(
                    List.of("chat-1", "chat-2", "chat-3"), gateway.shardIds());
        } finally {
            gateway.close();
        }
    }

    @Test
    void parsesExplicitShardIds() {
        ShardedChatGateway gateway =
                ShardedChatGateway.fromSpec("alpha@localhost:9091,beta@localhost:9091");
        try {
            Assertions.assertEquals(List.of("alpha", "beta"), gateway.shardIds());
            Assertions.assertTrue(
                    gateway.shardIds().contains(gateway.shardAssignment("alice").shard()));
            Assertions.assertEquals(2, gateway.shardAssignment("alice").shardCount());
        } finally {
            gateway.close();
        }
    }
}
