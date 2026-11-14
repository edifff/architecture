package ru.archlabs.proto;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import ru.archlabs.proto.chat.ChatServiceGrpc;
import ru.archlabs.proto.common.ChatMessage;
import ru.archlabs.proto.common.UserState;
import ru.archlabs.proto.storage.StorageServiceGrpc;

final class ProtoRoundTripTest {
    @Test
    void commonMessagesRoundTrip() throws Exception {
        ChatMessage message =
                ChatMessage.newBuilder()
                        .setId(7)
                        .setSender("alice")
                        .setText("hello")
                        .setCreatedAtEpochMillis(1_000)
                        .build();
        Assertions.assertEquals(message, ChatMessage.parseFrom(message.toByteArray()));

        UserState user =
                UserState.newBuilder()
                        .setName("alice")
                        .setOnline(true)
                        .setNotificationsEnabled(false)
                        .setLastSeenEpochMillis(2_000)
                        .build();
        Assertions.assertEquals(user, UserState.parseFrom(user.toByteArray()));
    }

    @Test
    void serviceContractsArePresent() {
        Assertions.assertEquals(
                "archlabs.chat.ChatService", ChatServiceGrpc.getServiceDescriptor().getName());
        Assertions.assertEquals(
                "archlabs.storage.StorageService",
                StorageServiceGrpc.getServiceDescriptor().getName());
        Assertions.assertTrue(
                ChatServiceGrpc.getServiceDescriptor().getMethods().stream()
                        .anyMatch(
                                method ->
                                        method.getFullMethodName()
                                                .equals("archlabs.chat.ChatService/Subscribe")));
    }
}
