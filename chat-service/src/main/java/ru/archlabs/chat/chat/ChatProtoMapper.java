package ru.archlabs.chat.chat;

import ru.archlabs.chat.domain.ChatMessage;
import ru.archlabs.chat.domain.ChatNotification;
import ru.archlabs.chat.domain.UserState;

import java.time.Instant;

final class ChatProtoMapper {
    private ChatProtoMapper() {
    }

    static ru.archlabs.proto.common.UserState toProto(UserState user) {
        return ru.archlabs.proto.common.UserState.newBuilder()
                .setName(user.name())
                .setOnline(user.online())
                .setNotificationsEnabled(user.notificationsEnabled())
                .setLastSeenEpochMillis(user.lastSeen().toEpochMilli())
                .build();
    }

    static UserState fromProto(ru.archlabs.proto.common.UserState user) {
        return new UserState(
                user.getName(),
                user.getOnline(),
                user.getNotificationsEnabled(),
                Instant.ofEpochMilli(user.getLastSeenEpochMillis()));
    }

    static ru.archlabs.proto.common.ChatMessage toProto(ChatMessage message) {
        return ru.archlabs.proto.common.ChatMessage.newBuilder()
                .setId(message.id())
                .setSender(message.sender())
                .setText(message.text())
                .setCreatedAtEpochMillis(message.createdAt().toEpochMilli())
                .build();
    }

    static ChatMessage fromProto(ru.archlabs.proto.common.ChatMessage message) {
        return new ChatMessage(
                message.getId(),
                message.getSender(),
                message.getText(),
                Instant.ofEpochMilli(message.getCreatedAtEpochMillis()));
    }

    static ru.archlabs.proto.common.ChatNotification toProto(ChatNotification notification) {
        return ru.archlabs.proto.common.ChatNotification.newBuilder()
                .setId(notification.id())
                .setRecipient(notification.recipient())
                .setMessage(toProto(notification.message()))
                .setCreatedAtEpochMillis(notification.createdAt().toEpochMilli())
                .build();
    }

    static ChatNotification fromProto(ru.archlabs.proto.common.ChatNotification notification) {
        return new ChatNotification(
                notification.getId(),
                notification.getRecipient(),
                fromProto(notification.getMessage()),
                Instant.ofEpochMilli(notification.getCreatedAtEpochMillis()));
    }
}
