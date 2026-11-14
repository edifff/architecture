package ru.archlabs.chat.storage;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

import ru.archlabs.chat.domain.ChatConflictException;
import ru.archlabs.chat.domain.ChatNotFoundException;
import ru.archlabs.chat.domain.ChatUser;
import ru.archlabs.chat.domain.ChatValidationException;
import ru.archlabs.chat.metrics.ChatMetrics;
import ru.archlabs.proto.common.ChatMessage;
import ru.archlabs.proto.common.ChatNotification;
import ru.archlabs.proto.common.Empty;
import ru.archlabs.proto.common.UserState;
import ru.archlabs.proto.storage.AppendMessageRequest;
import ru.archlabs.proto.storage.ConnectRequest;
import ru.archlabs.proto.storage.EnqueueNotificationRequest;
import ru.archlabs.proto.storage.HistoryRequest;
import ru.archlabs.proto.storage.HistoryResponse;
import ru.archlabs.proto.storage.MarkDeliveredRequest;
import ru.archlabs.proto.storage.NotificationsResponse;
import ru.archlabs.proto.storage.SetNotificationsRequest;
import ru.archlabs.proto.storage.StorageServiceGrpc;
import ru.archlabs.proto.storage.UserRequest;
import ru.archlabs.proto.storage.UsersResponse;

/**
 * Storage layer contract. It is the only layer that knows JDBC/H2.
 */
public final class StorageGrpcService extends StorageServiceGrpc.StorageServiceImplBase {
    private final ChatStorage storage;
    private final ChatMetrics metrics;

    public StorageGrpcService(ChatStorage storage, ChatMetrics metrics) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public void resetOnlineUsers(Empty request, StreamObserver<Empty> observer) {
        unary(
                observer,
                "storage_reset_online",
                () -> {
                    storage.resetOnlineUsers();
                    return Empty.getDefaultInstance();
                });
    }

    @Override
    public void connect(ConnectRequest request, StreamObserver<UserState> observer) {
        unary(
                observer,
                "storage_connect",
                () -> {
                    storage.connect(request.getName(), instant(request.getAtEpochMillis()));
                    return StorageProtoMapper.toProto(requireUser(request.getName()));
                });
    }

    @Override
    public void disconnect(ConnectRequest request, StreamObserver<UserState> observer) {
        unary(
                observer,
                "storage_disconnect",
                () -> {
                    storage.disconnect(request.getName(), instant(request.getAtEpochMillis()));
                    return StorageProtoMapper.toProto(requireUser(request.getName()));
                });
    }

    @Override
    public void setNotifications(SetNotificationsRequest request, StreamObserver<Empty> observer) {
        unary(
                observer,
                "storage_set_notifications",
                () -> {
                    storage.setNotificationsEnabled(request.getName(), request.getEnabled());
                    return Empty.getDefaultInstance();
                });
    }

    @Override
    public void findUser(UserRequest request, StreamObserver<UserState> observer) {
        unary(
                observer,
                "storage_find_user",
                () -> StorageProtoMapper.toProto(requireUser(request.getName())));
    }

    @Override
    public void listUsers(Empty request, StreamObserver<UsersResponse> observer) {
        unary(
                observer,
                "storage_list_users",
                () ->
                        UsersResponse.newBuilder()
                                .addAllUsers(
                                        storage.allUsers().stream()
                                                .map(StorageProtoMapper::toProto)
                                                .toList())
                                .build());
    }

    @Override
    public void appendMessage(AppendMessageRequest request, StreamObserver<ChatMessage> observer) {
        unary(
                observer,
                "storage_append_message",
                () ->
                        StorageProtoMapper.toProto(
                                storage.appendMessage(
                                        request.getSender(),
                                        request.getText(),
                                        request.getRecipientsList(),
                                        instant(request.getAtEpochMillis()))));
    }

    @Override
    public void history(HistoryRequest request, StreamObserver<HistoryResponse> observer) {
        unary(
                observer,
                "storage_history",
                () ->
                        HistoryResponse.newBuilder()
                                .addAllMessages(
                                        storage.history(request.getLimit()).stream()
                                                .map(StorageProtoMapper::toProto)
                                                .toList())
                                .build());
    }

    @Override
    public void enqueueNotification(
            EnqueueNotificationRequest request,
            StreamObserver<ChatNotification> observer) {
        unary(
                observer,
                "storage_enqueue_notification",
                () ->
                        StorageProtoMapper.toProto(
                                storage.enqueueNotification(
                                        request.getRecipient(),
                                        StorageProtoMapper.fromProto(request.getMessage()),
                                        instant(request.getAtEpochMillis()))));
    }

    @Override
    public void pendingNotifications(UserRequest request, StreamObserver<NotificationsResponse> observer) {
        unary(
                observer,
                "storage_pending_notifications",
                () ->
                        NotificationsResponse.newBuilder()
                                .addAllNotifications(
                                        storage.pendingNotifications(request.getName()).stream()
                                                .map(StorageProtoMapper::toProto)
                                                .toList())
                                .build());
    }

    @Override
    public void markNotificationDelivered(MarkDeliveredRequest request, StreamObserver<Empty> observer) {
        unary(
                observer,
                "storage_mark_delivered",
                () -> {
                    storage.markNotificationDelivered(request.getId(), instant(request.getAtEpochMillis()));
                    return Empty.getDefaultInstance();
                });
    }

    private ChatUser requireUser(String name) {
        return storage.findUser(name)
                .orElseThrow(() -> new ChatNotFoundException("Unknown user: " + name));
    }

    private <T> void unary(StreamObserver<T> observer, String operation, Supplier<T> action) {
        metrics.requestProcessed(operation);
        try {
            observer.onNext(action.get());
            observer.onCompleted();
        } catch (RuntimeException exception) {
            observer.onError(status(exception));
        }
    }

    private static StatusRuntimeException status(RuntimeException exception) {
        String description = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        if (exception instanceof ChatValidationException) {
            return Status.INVALID_ARGUMENT.withDescription(description).asRuntimeException();
        }
        if (exception instanceof ChatNotFoundException) {
            return Status.NOT_FOUND.withDescription(description).asRuntimeException();
        }
        if (exception instanceof ChatConflictException) {
            return Status.FAILED_PRECONDITION.withDescription(description).asRuntimeException();
        }
        return Status.INTERNAL.withDescription(description).asRuntimeException();
    }

    private static Instant instant(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis);
    }
}
