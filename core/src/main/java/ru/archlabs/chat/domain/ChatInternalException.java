package ru.archlabs.chat.domain;

public final class ChatInternalException extends ChatException {
    public ChatInternalException(String message) {
        super(message);
    }

    public ChatInternalException(String message, Throwable cause) {
        super(message, cause);
    }
}
