package ru.archlabs.chat.domain;

public final class ChatConflictException extends ChatException {
    public ChatConflictException(String message) {
        super(message);
    }
}
