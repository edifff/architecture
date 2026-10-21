package ru.archlabs.chat.storage;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import ru.archlabs.proto.common.Empty;
import ru.archlabs.proto.storage.AppendRequest;
import ru.archlabs.proto.storage.ChatStorageGrpc;
import ru.archlabs.proto.storage.ClearNotificationsRequest;
import ru.archlabs.proto.storage.ConnectRequest;
import ru.archlabs.proto.storage.DisconnectRequest;
import ru.archlabs.proto.storage.HistoryRequest;
import ru.archlabs.proto.storage.MessagesResponse;
import ru.archlabs.proto.storage.SetNotificationsEnabledRequest;
import ru.archlabs.proto.storage.UndeliveredNotificationsRequest;
import ru.archlabs.proto.storage.UserRequest;
import ru.archlabs.proto.storage.UserStatesResponse;

import java.time.Instant;
import java.util.List;

/**
 * Слой хранения как gRPC-сервис: контракт {@code storage.ChatStorage} поверх
 * доменного интерфейса {@link ChatStorage} (реализация — {@link PostgresChatStorage}).
 * Доменные исключения маппятся на статусы по соглашению из {@code proto/README.md}.
 */
public final class StorageGrpcService extends ChatStorageGrpc.ChatStorageImplBase {
    private final ChatStorage storage;

    public StorageGrpcService(ChatStorage storage) {
        this.storage = storage;
    }

    @Override
    public void connect(ConnectRequest request, StreamObserver<Empty> observer) {
        run(observer, () -> storage.connect(request.getName(), instant(request.getAt())));
    }

    @Override
    public void disconnect(DisconnectRequest request, StreamObserver<Empty> observer) {
        run(observer, () -> storage.disconnect(request.getName(), instant(request.getAt())));
    }

    @Override
    public void setNotificationsEnabled(
            SetNotificationsEnabledRequest request, StreamObserver<Empty> observer) {
        run(observer, () -> storage.setNotificationsEnabled(request.getName(), request.getEnabled()));
    }

    @Override
    public void append(AppendRequest request, StreamObserver<ru.archlabs.proto.common.Message> observer) {
        try {
            var stored =
                    storage.append(
                            request.getSender(), request.getText(), instant(request.getSentAt()));
            observer.onNext(toProto(stored));
            observer.onCompleted();
        } catch (RuntimeException exception) {
            observer.onError(map(exception));
        }
    }

    @Override
    public void undeliveredNotifications(
            UndeliveredNotificationsRequest request, StreamObserver<MessagesResponse> observer) {
        try {
            observer.onNext(messages(storage.undeliveredNotifications(request.getRecipient())));
            observer.onCompleted();
        } catch (RuntimeException exception) {
            observer.onError(map(exception));
        }
    }

    @Override
    public void clearNotifications(
            ClearNotificationsRequest request, StreamObserver<Empty> observer) {
        run(
                observer,
                () ->
                        storage.clearNotifications(
                                request.getRecipient(), request.getMessageIdsList()));
    }

    @Override
    public void history(HistoryRequest request, StreamObserver<MessagesResponse> observer) {
        try {
            observer.onNext(messages(storage.history()));
            observer.onCompleted();
        } catch (RuntimeException exception) {
            observer.onError(map(exception));
        }
    }

    @Override
    public void user(UserRequest request, StreamObserver<ru.archlabs.proto.common.UserState> observer) {
        try {
            var user = storage.user(request.getName());
            if (user.isEmpty()) {
                observer.onError(Status.NOT_FOUND.withDescription("Unknown user: " + request.getName()).asRuntimeException());
                return;
            }
            observer.onNext(toProto(user.orElseThrow()));
            observer.onCompleted();
        } catch (RuntimeException exception) {
            observer.onError(map(exception));
        }
    }

    @Override
    public void onlineUsersWithNotificationsEnabled(
            Empty request, StreamObserver<UserStatesResponse> observer) {
        try {
            var users =
                    storage.onlineUsersWithNotificationsEnabled().stream()
                            .map(StorageGrpcService::toProto)
                            .toList();
            observer.onNext(UserStatesResponse.newBuilder().addAllUsers(users).build());
            observer.onCompleted();
        } catch (RuntimeException exception) {
            observer.onError(map(exception));
        }
    }

    @Override
    public void resetOnlineUsers(Empty request, StreamObserver<Empty> observer) {
        run(observer, storage::resetOnlineUsers);
    }

    private static void run(StreamObserver<Empty> observer, Runnable operation) {
        try {
            operation.run();
            observer.onNext(Empty.getDefaultInstance());
            observer.onCompleted();
        } catch (RuntimeException exception) {
            observer.onError(map(exception));
        }
    }

    private static MessagesResponse messages(List<ru.archlabs.chat.Message> messages) {
        return MessagesResponse.newBuilder()
                .addAllMessages(messages.stream().map(StorageGrpcService::toProto).toList())
                .build();
    }

    private static ru.archlabs.proto.common.Message toProto(ru.archlabs.chat.Message message) {
        return ru.archlabs.proto.common.Message.newBuilder()
                .setId(message.id())
                .setSender(message.sender())
                .setText(message.text())
                .setSentAt(timestamp(message.sentAt()))
                .build();
    }

    private static ru.archlabs.proto.common.UserState toProto(ru.archlabs.chat.UserState user) {
        var builder =
                ru.archlabs.proto.common.UserState.newBuilder()
                        .setName(user.name())
                        .setOnline(user.online())
                        .setNotificationsEnabled(user.notificationsEnabled());
        if (user.lastConnected() != null) {
            builder.setLastConnected(timestamp(user.lastConnected()));
        }
        return builder.build();
    }

    private static Instant instant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static StatusRuntimeException map(RuntimeException exception) {
        if (exception instanceof StorageException) {
            return Status.INTERNAL.withDescription(message(exception)).withCause(exception).asRuntimeException();
        }
        if (exception instanceof IllegalArgumentException) {
            var message = message(exception);
            if (message.startsWith("Unknown user")) {
                return Status.NOT_FOUND.withDescription(message).withCause(exception).asRuntimeException();
            }
            return Status.INVALID_ARGUMENT.withDescription(message).withCause(exception).asRuntimeException();
        }
        if (exception instanceof IllegalStateException) {
            return Status.FAILED_PRECONDITION.withDescription(message(exception)).withCause(exception).asRuntimeException();
        }
        return Status.INTERNAL.withDescription(message(exception)).withCause(exception).asRuntimeException();
    }

    private static String message(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
