package ru.archlabs.chat.storage;

import java.time.Instant;

import ru.archlabs.chat.domain.ChatUser;
import ru.archlabs.chat.domain.Message;
import ru.archlabs.chat.domain.Notification;
import ru.archlabs.proto.common.ChatMessage;
import ru.archlabs.proto.common.ChatNotification;
import ru.archlabs.proto.common.UserState;

final class StorageProtoMapper {
    private StorageProtoMapper() {
    }

    static UserState toProto(ChatUser user) {
        return UserState.newBuilder()
                .setName(user.name())
                .setOnline(user.online())
                .setNotificationsEnabled(user.notificationsEnabled())
                .setLastSeenEpochMillis(user.lastSeen().toEpochMilli())
                .build();
    }

    static ChatUser fromProto(UserState user) {
        return new ChatUser(
                user.getName(),
                user.getOnline(),
                user.getNotificationsEnabled(),
                Instant.ofEpochMilli(user.getLastSeenEpochMillis()));
    }

    static ChatMessage toProto(Message message) {
        return ChatMessage.newBuilder()
                .setId(message.id())
                .setSender(message.sender())
                .setText(message.text())
                .setCreatedAtEpochMillis(message.createdAt().toEpochMilli())
                .build();
    }

    static Message fromProto(ChatMessage message) {
        return new Message(
                message.getId(),
                message.getSender(),
                message.getText(),
                Instant.ofEpochMilli(message.getCreatedAtEpochMillis()));
    }

    static ChatNotification toProto(Notification notification) {
        return ChatNotification.newBuilder()
                .setId(notification.id())
                .setRecipient(notification.recipient())
                .setMessage(toProto(notification.message()))
                .setCreatedAtEpochMillis(notification.createdAt().toEpochMilli())
                .build();
    }

    static Notification fromProto(ChatNotification notification) {
        return new Notification(
                notification.getId(),
                notification.getRecipient(),
                fromProto(notification.getMessage()),
                Instant.ofEpochMilli(notification.getCreatedAtEpochMillis()));
    }
}
