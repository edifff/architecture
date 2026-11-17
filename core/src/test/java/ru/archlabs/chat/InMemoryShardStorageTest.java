package ru.archlabs.chat;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import ru.archlabs.chat.domain.Message;
import ru.archlabs.chat.storage.InMemoryChatStorage;

final class InMemoryShardStorageTest {
    @Test
    void shardsProduceDisjointMessageIds() {
        Set<Long> ids = new HashSet<Long>();
        for (int shard = 0; shard < 3; shard++) {
            InMemoryChatStorage storage = new InMemoryChatStorage(shard, 3);
            for (int index = 0; index < 5; index++) {
                Message message =
                        storage.appendMessage(
                                "user-" + shard,
                                "message-" + index,
                                List.of(),
                                Instant.EPOCH);
                ids.add(message.id());
            }
        }

        Assertions.assertEquals(15, ids.size());
    }
}
