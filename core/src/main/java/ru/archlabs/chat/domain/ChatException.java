package ru.archlabs.chat.domain;

/**
 * Base class for expected chat failures.
 */
public sealed class ChatException extends RuntimeException
        permits ChatValidationException,
                ChatConflictException,
                ChatNotFoundException,
                ChatInternalException {

    protected ChatException(String message) {
        super(message);
    }

    protected ChatException(String message, Throwable cause) {
        super(message, cause);
    }
}
