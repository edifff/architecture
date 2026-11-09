package ru.archlabs.chat.domain;

public final class ChatNotFoundException extends ChatException {
    public ChatNotFoundException(String message) {
        super(message);
    }
}
