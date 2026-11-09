package ru.archlabs.proto;

import org.junit.jupiter.api.Test;
import ru.archlabs.proto.chat.ChatServiceGrpc;
import ru.archlabs.proto.common.ChatMessage;
import ru.archlabs.proto.common.UserState;
import ru.archlabs.proto.storage.StorageServiceGrpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProtoRoundTripTest {
    @Test
    void commonMessagesRoundTrip() throws Exception {
        var message =
                ChatMessage.newBuilder()
                        .setId(7)
                        .setSender("alice")
                        .setText("hello")
                        .setCreatedAtEpochMillis(1_000)
                        .build();
        assertEquals(message, ChatMessage.parseFrom(message.toByteArray()));

        var user =
                UserState.newBuilder()
                        .setName("alice")
                        .setOnline(true)
                        .setNotificationsEnabled(false)
                        .setLastSeenEpochMillis(2_000)
                        .build();
        assertEquals(user, UserState.parseFrom(user.toByteArray()));
    }

    @Test
    void serviceContractsArePresent() {
        assertEquals("archlabs.chat.ChatService", ChatServiceGrpc.getServiceDescriptor().getName());
        assertEquals(
                "archlabs.storage.StorageService",
                StorageServiceGrpc.getServiceDescriptor().getName());
        assertTrue(
                ChatServiceGrpc.getServiceDescriptor().getMethods().stream()
                        .anyMatch(
                                method ->
                                        method.getFullMethodName()
                                                .equals("archlabs.chat.ChatService/Subscribe")));
    }
}
