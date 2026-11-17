package ru.archlabs.chat.api;

import io.grpc.StatusRuntimeException;

import ru.archlabs.chat.domain.ChatConflictException;
import ru.archlabs.chat.domain.ChatInternalException;
import ru.archlabs.chat.domain.ChatNotFoundException;
import ru.archlabs.chat.domain.ChatValidationException;

final class GrpcErrors {
    private GrpcErrors() {
    }

    static RuntimeException map(StatusRuntimeException exception) {
        String description = exception.getStatus().getDescription();
        String message = description == null ? exception.getStatus().getCode().name() : description;
        return switch (exception.getStatus().getCode()) {
            case INVALID_ARGUMENT -> new ChatValidationException(message);
            case NOT_FOUND -> new ChatNotFoundException(message);
            case ALREADY_EXISTS, FAILED_PRECONDITION -> new ChatConflictException(message);
            default -> new ChatInternalException(message, exception);
        };
    }
}
