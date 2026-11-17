package ru.archlabs.chat.api;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import ru.archlabs.chat.domain.ChatValidationException;

final class ShardRouterTest {
    @Test
    void mappingIsStableForTheSameKey() {
        ShardRouter first = new ShardRouter(3);
        ShardRouter second = new ShardRouter(3);

        Assertions.assertEquals(2, first.indexOf("alice"));
        Assertions.assertEquals(0, first.indexOf("bob"));
        Assertions.assertEquals(1, first.indexOf("carol"));
        Assertions.assertEquals(first.indexOf("alice"), second.indexOf("alice"));
        Assertions.assertEquals(first.indexOf("alice"), first.indexOf(" alice "));
    }

    @Test
    void distributesManyKeysAcrossEveryShard() {
        ShardRouter router = new ShardRouter(3);
        int[] counts = new int[3];
        for (int index = 0; index < 300; index++) {
            counts[router.indexOf("user-" + index)]++;
        }

        for (int count : counts) {
            Assertions.assertTrue(count > 50, "Shard distribution is too skewed");
        }
    }

    @Test
    void rejectsBlankKeysAndInvalidShardCounts() {
        ShardRouter router = new ShardRouter(2);

        Assertions.assertThrows(ChatValidationException.class, () -> router.indexOf("  "));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new ShardRouter(0));
    }
}
