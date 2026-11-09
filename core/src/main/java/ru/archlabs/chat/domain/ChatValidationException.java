package ru.archlabs.chat.domain;

public final class ChatValidationException extends ChatException {
    public ChatValidationException(String message) {
        super(message);
    }
}
