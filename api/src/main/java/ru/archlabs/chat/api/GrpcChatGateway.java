package ru.archlabs.chat.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;

import ru.archlabs.chat.domain.ChatConflictException;
import ru.archlabs.chat.domain.ChatInternalException;
import ru.archlabs.chat.domain.ChatNotFoundException;
import ru.archlabs.chat.domain.ChatUser;
import ru.archlabs.chat.domain.ChatValidationException;
import ru.archlabs.chat.domain.Message;
import ru.archlabs.chat.metrics.ChatMetrics;
import ru.archlabs.proto.chat.ChatServiceGrpc;
import ru.archlabs.proto.chat.ConnectRequest;
import ru.archlabs.proto.chat.DeliveryReport;
import ru.archlabs.proto.chat.HistoryRequest;
import ru.archlabs.proto.chat.SendMessageRequest;
import ru.archlabs.proto.chat.SetNotificationsRequest;
import ru.archlabs.proto.chat.StatusSendResponse;
import ru.archlabs.proto.chat.UserRequest;
import ru.archlabs.proto.common.ChatMessage;
import ru.archlabs.proto.common.UserState;

/**
 *  the input layer talks to the domain layer over gRPC.
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
    public ChatUser connect(String user) {
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
    public Message send(String user, String text) {
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
    public List<Message> history(String user, int limit) {
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
    public ChatUser user(String user) {
        metrics.requestProcessed("api_get_user");
        try {
            return map(blocking.getUser(UserRequest.newBuilder().setName(user).build()));
        } catch (StatusRuntimeException exception) {
            throw map(exception);
        }
    }

    @Override
    public AutoCloseable subscribe(String user, Consumer<Message> listener) {
        metrics.requestProcessed("api_subscribe");
        AtomicReference<ClientCallStreamObserver<UserRequest>>
                requestStream = new AtomicReference<>();
        ClientResponseObserver<UserRequest, ChatMessage> observer =
                new ClientResponseObserver<UserRequest, ChatMessage>() {
                    @Override
                    public void beforeStart(ClientCallStreamObserver<UserRequest> stream) {
                        requestStream.set(stream);
                    }

                    @Override
                    public void onNext(ChatMessage value) {
                        metrics.notificationDelivered("live", 1);
                        listener.accept(map(value));
                    }

                    @Override
                    public void onError(Throwable throwable) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                };
        async.subscribe(UserRequest.newBuilder().setName(user).build(), observer);
        return () -> {
            ClientCallStreamObserver<UserRequest> stream = requestStream.get();
            if (stream != null) {
                stream.cancel("client closed", null);
            }
        };
    }

    @Override
    public StatusReport broadcastStatus(String user, String text) {
        metrics.requestProcessed("api_status");
        try {
            StatusSendResponse response =
                    blocking.sendStatus(
                            SendMessageRequest.newBuilder()
                                    .setName(user)
                                    .setText(text)
                                    .build());
            DeliveryReport delivery = response.getDelivery();
            ShardReport report =
                    new ShardReport(
                            delivery.getShard(),
                            delivery.getChats(),
                            delivery.getDelivered(),
                            delivery.getQueued());
            return new StatusReport(
                    user,
                    map(response.getMessage()),
                    1,
                    delivery.getChats(),
                    delivery.getDelivered(),
                    delivery.getQueued(),
                    List.of(report));
        } catch (StatusRuntimeException exception) {
            throw map(exception);
        }
    }

    @Override
    public ShardAssignment shardAssignment(String user) {
        return new ShardAssignment(user, "chat", 1);
    }

    @Override
    public ChatMetrics metrics() {
        return metrics;
    }

    @Override
    public void close() {
        channel.shutdownNow();
    }

    private static ChatUser map(UserState user) {
        return new ChatUser(
                user.getName(),
                user.getOnline(),
                user.getNotificationsEnabled(),
                Instant.ofEpochMilli(user.getLastSeenEpochMillis()));
    }

    private static Message map(ChatMessage message) {
        return new Message(
                message.getId(),
                message.getSender(),
                message.getText(),
                Instant.ofEpochMilli(message.getCreatedAtEpochMillis()));
    }

    private static RuntimeException map(StatusRuntimeException exception) {
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
