package ru.archlabs.chat.api;

import ru.archlabs.proto.common.Message;

import java.time.Instant;

public record MessagePayload(long id, String sender, String text, String sentAt) {
    public static MessagePayload from(Message message) {
        return new MessagePayload(
                message.getId(),
                message.getSender(),
                message.getText(),
                Instant.ofEpochSecond(
                                message.getSentAt().getSeconds(), message.getSentAt().getNanos())
                        .toString());
    }
}
