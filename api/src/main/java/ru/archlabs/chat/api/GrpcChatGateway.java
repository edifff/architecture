package ru.archlabs.chat.api;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import ru.archlabs.chat.domain.ChatConflictException;
import ru.archlabs.chat.domain.ChatInternalException;
import ru.archlabs.chat.domain.ChatMessage;
import ru.archlabs.chat.domain.ChatNotFoundException;
import ru.archlabs.chat.domain.ChatValidationException;
import ru.archlabs.chat.domain.UserState;
import ru.archlabs.chat.metrics.ChatMetrics;
import ru.archlabs.proto.chat.ChatServiceGrpc;
import ru.archlabs.proto.chat.ConnectRequest;
import ru.archlabs.proto.chat.HistoryRequest;
import ru.archlabs.proto.chat.SendMessageRequest;
import ru.archlabs.proto.chat.SetNotificationsRequest;
import ru.archlabs.proto.chat.UserRequest;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Lab 5 gateway: the input layer talks to the domain layer over gRPC.
 */
public final class GrpcChatGateway implements ChatGateway {
    private final ManagedChannel channel;
    private final ChatServiceGrpc.ChatServiceBlockingStub blocking;
    private final ChatServiceGrpc.ChatServiceStub async;
    private final ChatMetrics metrics = new ChatMetrics();

    public GrpcChatGateway(String host, int port) {
        this(ManagedChannelBuilder.forAddress(host, port).usePlaintext().build());
    }

    public GrpcChatGateway(ManagedChannel channel) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.blocking = ChatServiceGrpc.newBlockingStub(channel);
        this.async = ChatServiceGrpc.newStub(channel);
    }

    @Override
    public UserState connect(String user) {
        metrics.requestProcessed("api_connect");
        try {
            return map(
                    blocking.connect(ConnectRequest.newBuilder().setName(user).build()));
        } catch (StatusRuntimeException exception) {
            throw map(exception);
        }
    }

    @Override
    public void disconnect(String user) {
        metrics.requestProcessed("api_disconnect");
        try {
            blocking.disconnect(UserRequest.newBuilder().setName(user).build());
        } catch (StatusRuntimeException exception) {
            throw map(exception);
        }
    }

    @Override
    public ChatMessage send(String user, String text) {
        metrics.requestProcessed("api_send");
        try {
            return map(
                    blocking.send(
                            SendMessageRequest.newBuilder()
                                    .setName(user)
                                    .setText(text)
                                    .build()));
        } catch (StatusRuntimeException exception) {
            throw map(exception);
        }
    }

    @Override
    public List<ChatMessage> history(String user, int limit) {
        metrics.requestProcessed("api_history");
        try {
            return blocking.history(
                            HistoryRequest.newBuilder().setName(user).setLimit(limit).build())
                    .getMessagesList()
                    .stream()
                    .map(GrpcChatGateway::map)
                    .toList();
        } catch (StatusRuntimeException exception) {
            throw map(exception);
        }
    }

    @Override
    public void setNotificationsEnabled(String user, boolean enabled) {
        metrics.requestProcessed("api_set_notifications");
        try {
            blocking.setNotifications(
                    SetNotificationsRequest.newBuilder()
                            .setName(user)
                            .setEnabled(enabled)
                            .build());
        } catch (StatusRuntimeException exception) {
            throw map(exception);
        }
    }

    @Override
    public UserState user(String user) {
        metrics.requestProcessed("api_get_user");
        try {
            return map(blocking.getUser(UserRequest.newBuilder().setName(user).build()));
        } catch (StatusRuntimeException exception) {
            throw map(exception);
        }
    }

    @Override
    public AutoCloseable subscribe(String user, Consumer<ChatMessage> listener) {
        metrics.requestProcessed("api_subscribe");
        var observer =
                new ClientResponseObserver<UserRequest, ru.archlabs.proto.common.ChatMessage>() {
                    private ClientCallStreamObserver<UserRequest> requestStream;

                    @Override
                    public void beforeStart(ClientCallStreamObserver<UserRequest> requestStream) {
                        this.requestStream = requestStream;
                    }

                    @Override
                    public void onNext(ru.archlabs.proto.common.ChatMessage value) {
                        metrics.notificationDelivered("live", 1);
                        listener.accept(map(value));
                    }

                    @Override
                    public void onError(Throwable throwable) {
                    }

                    @Override
                    public void onCompleted() {
                    }

                    private void cancel() {
                        if (requestStream != null) {
                            requestStream.cancel("client closed", null);
                        }
                    }
                };
        async.subscribe(UserRequest.newBuilder().setName(user).build(), observer);
        return observer::cancel;
    }

    @Override
    public ChatMetrics metrics() {
        return metrics;
    }

    @Override
    public void close() {
        channel.shutdownNow();
    }

    private static UserState map(ru.archlabs.proto.common.UserState user) {
        return new UserState(
                user.getName(),
                user.getOnline(),
                user.getNotificationsEnabled(),
                java.time.Instant.ofEpochMilli(user.getLastSeenEpochMillis()));
    }

    private static ChatMessage map(ru.archlabs.proto.common.ChatMessage message) {
        return new ChatMessage(
                message.getId(),
                message.getSender(),
                message.getText(),
                java.time.Instant.ofEpochMilli(message.getCreatedAtEpochMillis()));
    }

    private static RuntimeException map(StatusRuntimeException exception) {
        var description = exception.getStatus().getDescription();
        var message = description == null ? exception.getStatus().getCode().name() : description;
        return switch (exception.getStatus().getCode()) {
            case INVALID_ARGUMENT -> new ChatValidationException(message);
            case NOT_FOUND -> new ChatNotFoundException(message);
            case ALREADY_EXISTS, FAILED_PRECONDITION -> new ChatConflictException(message);
            default -> new ChatInternalException(message, exception);
        };
    }

}
