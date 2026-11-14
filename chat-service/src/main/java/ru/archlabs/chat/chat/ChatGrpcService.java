package ru.archlabs.chat.chat;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

import ru.archlabs.chat.ChatServer;
import ru.archlabs.chat.ChatSession;
import ru.archlabs.chat.domain.ChatConflictException;
import ru.archlabs.chat.domain.ChatNotFoundException;
import ru.archlabs.chat.domain.ChatValidationException;
import ru.archlabs.proto.chat.ChatServiceGrpc;
import ru.archlabs.proto.chat.ConnectRequest;
import ru.archlabs.proto.chat.HistoryRequest;
import ru.archlabs.proto.chat.HistoryResponse;
import ru.archlabs.proto.chat.SendMessageRequest;
import ru.archlabs.proto.chat.SetNotificationsRequest;
import ru.archlabs.proto.chat.UserRequest;
import ru.archlabs.proto.common.ChatMessage;
import ru.archlabs.proto.common.Empty;
import ru.archlabs.proto.common.UserState;

/**
 * Domain layer contract over gRPC. The domain has no Netty, HTTP, or JDBC code.
 */
public final class ChatGrpcService extends ChatServiceGrpc.ChatServiceImplBase {
    private static final String NOT_CONNECTED = "User is not connected: ";

    private final ChatServer server;
    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();

    public ChatGrpcService(ChatServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public void connect(ConnectRequest request, StreamObserver<UserState> observer) {
        unary(
                observer,
                () -> {
                    ChatSession session = server.connect(request.getName());
                    sessions.put(session.userName(), session);
                    return ChatProtoMapper.toProto(server.user(session.userName()));
                });
    }

    @Override
    public void disconnect(UserRequest request, StreamObserver<Empty> observer) {
        unary(
                observer,
                () -> {
                    ChatSession session = sessions.remove(request.getName());
                    if (session == null) {
                        throw new ChatNotFoundException(NOT_CONNECTED + request.getName());
                    }
                    session.close();
                    return Empty.getDefaultInstance();
                });
    }

    @Override
    public void send(SendMessageRequest request, StreamObserver<ChatMessage> observer) {
        unary(
                observer,
                () ->
                        ChatProtoMapper.toProto(
                                requireSession(request.getName()).send(request.getText())));
    }

    @Override
    public void history(HistoryRequest request, StreamObserver<HistoryResponse> observer) {
        unary(
                observer,
                () ->
                        HistoryResponse.newBuilder()
                                .addAllMessages(
                                        requireSession(request.getName())
                                                .history(request.getLimit())
                                                .stream()
                                                .map(ChatProtoMapper::toProto)
                                                .toList())
                                .build());
    }

    @Override
    public void setNotifications(SetNotificationsRequest request, StreamObserver<Empty> observer) {
        unary(
                observer,
                () -> {
                    requireSession(request.getName())
                            .setNotificationsEnabled(request.getEnabled());
                    return Empty.getDefaultInstance();
                });
    }

    @Override
    public void getUser(UserRequest request, StreamObserver<UserState> observer) {
        unary(observer, () -> ChatProtoMapper.toProto(server.user(request.getName())));
    }

    @Override
    public void subscribe(UserRequest request, StreamObserver<ChatMessage> responseObserver) {
        ServerCallStreamObserver<ChatMessage> observer = (ServerCallStreamObserver<ChatMessage>) responseObserver;
        try {
            ChatSession session = requireSession(request.getName());
            AutoCloseable subscription =
                    session.addListener(
                            message -> {
                                if (!observer.isCancelled()) {
                                    observer.onNext(ChatProtoMapper.toProto(message));
                                }
                            });
            observer.setOnCancelHandler(() -> closeQuietly(subscription));
        } catch (RuntimeException exception) {
            responseObserver.onError(status(exception));
        }
    }

    private ChatSession requireSession(String name) {
        ChatSession session = sessions.get(name);
        if (session == null) {
            throw new ChatNotFoundException(NOT_CONNECTED + name);
        }
        return session;
    }

    private <T> void unary(StreamObserver<T> observer, Supplier<T> action) {
        try {
            observer.onNext(action.get());
            observer.onCompleted();
        } catch (RuntimeException exception) {
            observer.onError(status(exception));
        }
    }

    private static StatusRuntimeException status(RuntimeException exception) {
        String description =
                exception.getMessage() == null
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage();
        if (exception instanceof ChatValidationException) {
            return Status.INVALID_ARGUMENT.withDescription(description).asRuntimeException();
        }
        if (exception instanceof ChatNotFoundException) {
            return Status.NOT_FOUND.withDescription(description).asRuntimeException();
        }
        if (exception instanceof ChatConflictException) {
            return Status.ALREADY_EXISTS.withDescription(description).asRuntimeException();
        }
        return Status.INTERNAL.withDescription(description).asRuntimeException();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
