package ru.archlabs.chat.chat;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import ru.archlabs.chat.ChatServer;
import ru.archlabs.chat.ChatSession;
import ru.archlabs.chat.Message;
import ru.archlabs.chat.UserState;
import ru.archlabs.proto.chat.ChatServiceGrpc;
import ru.archlabs.proto.chat.ConnectRequest;
import ru.archlabs.proto.chat.ConnectResponse;
import ru.archlabs.proto.chat.DisconnectRequest;
import ru.archlabs.proto.chat.HistoryRequest;
import ru.archlabs.proto.chat.MessagesResponse;
import ru.archlabs.proto.chat.SendRequest;
import ru.archlabs.proto.chat.SetNotificationsEnabledRequest;
import ru.archlabs.proto.chat.SubscribeRequest;
import ru.archlabs.proto.chat.UserRequest;
import ru.archlabs.proto.common.Empty;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class ChatGrpcService extends ChatServiceGrpc.ChatServiceImplBase {
    private static final int QUEUE_CAPACITY = 10_000;

    private final ChatServer chat;
    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, BlockingQueue<Message>> pending = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.atomic.AtomicBoolean> subscriptionStops =
            new ConcurrentHashMap<>();

    public ChatGrpcService(ChatServer chat) {
        this.chat = chat;
    }

    @Override
    public void connect(ConnectRequest request, StreamObserver<ConnectResponse> observer) {
        var name = request.getName();
        var queue = new LinkedBlockingQueue<Message>(QUEUE_CAPACITY);
        try {
            var session = chat.connect(name, queue::offer);
            sessions.put(name, session);
            pending.put(name, queue);
            observer.onNext(ConnectResponse.newBuilder().setUser(toProto(chat.user(name))).build());
            observer.onCompleted();
        } catch (RuntimeException exception) {
            observer.onError(map(exception, Status.Code.ALREADY_EXISTS));
        }
    }

    @Override
    public void disconnect(DisconnectRequest request, StreamObserver<Empty> observer) {
        var name = request.getName();
        var session = sessions.remove(name);
        pending.remove(name);
        var stop = subscriptionStops.remove(name);
        if (stop != null) {
            stop.set(true);
        }
        if (session == null) {
            observer.onError(
                    Status.NOT_FOUND.withDescription("Unknown user: " + name).asRuntimeException());
            return;
        }
        try {
            session.close();
            observer.onNext(Empty.getDefaultInstance());
            observer.onCompleted();
        } catch (RuntimeException exception) {
            observer.onError(map(exception, Status.Code.NOT_FOUND));
        }
    }

    @Override
    public void send(SendRequest request, StreamObserver<ru.archlabs.proto.common.Message> observer) {
        try {
            var message = requireSession(request.getName()).send(request.getText());
            observer.onNext(toProto(message));
            observer.onCompleted();
        } catch (RuntimeException exception) {
            observer.onError(map(exception, Status.Code.FAILED_PRECONDITION));
        }
    }

    @Override
    public void history(HistoryRequest request, StreamObserver<MessagesResponse> observer) {
        try {
            List<Message> history = requireSession(request.getName()).history();
            observer.onNext(
                    MessagesResponse.newBuilder()
                            .addAllMessages(history.stream().map(ChatGrpcService::toProto).toList())
                            .build());
            observer.onCompleted();
        } catch (RuntimeException exception) {
            observer.onError(map(exception, Status.Code.FAILED_PRECONDITION));
        }
    }

    @Override
    public void setNotificationsEnabled(
            SetNotificationsEnabledRequest request, StreamObserver<Empty> observer) {
        try {
            requireSession(request.getName()).setNotificationsEnabled(request.getEnabled());
            observer.onNext(Empty.getDefaultInstance());
            observer.onCompleted();
        } catch (RuntimeException exception) {
            observer.onError(map(exception, Status.Code.FAILED_PRECONDITION));
        }
    }

    @Override
    public void user(UserRequest request, StreamObserver<ru.archlabs.proto.common.UserState> observer) {
        try {
            observer.onNext(toProto(chat.user(request.getName())));
            observer.onCompleted();
        } catch (RuntimeException exception) {
            observer.onError(map(exception, Status.Code.NOT_FOUND));
        }
    }

    @Override
    public void subscribe(
            SubscribeRequest request, StreamObserver<ru.archlabs.proto.common.Message> observer) {
        var name = request.getName();
        var queue = pending.get(name);
        if (queue == null || !sessions.containsKey(name)) {
            observer.onError(
                    Status.FAILED_PRECONDITION
                            .withDescription("Session is not active: " + name)
                            .asRuntimeException());
            return;
        }

        var call = (ServerCallStreamObserver<ru.archlabs.proto.common.Message>) observer;
        var stop = new java.util.concurrent.atomic.AtomicBoolean(false);
        subscriptionStops.put(name, stop);
        call.setOnCancelHandler(() -> stop.set(true));

        var thread =
                new Thread(
                        () -> {
                            try {
                                while (!stop.get() && !call.isCancelled()) {
                                    var message = queue.poll(100, TimeUnit.MILLISECONDS);
                                    if (message != null) {
                                        call.onNext(toProto(message));
                                    }
                                }
                                call.onCompleted();
                            } catch (RuntimeException exception) {
                                stop.set(true);
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                stop.set(true);
                            }
                        },
                        "chat-subscribe-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    private ChatSession requireSession(String name) {
        var session = sessions.get(name);
        if (session == null) {
            throw new IllegalStateException("Session is not active: " + name);
        }
        return session;
    }

    private static ru.archlabs.proto.common.Message toProto(Message message) {
        return ru.archlabs.proto.common.Message.newBuilder()
                .setId(message.id())
                .setSender(message.sender())
                .setText(message.text())
                .setSentAt(timestamp(message.sentAt()))
                .build();
    }

    private static ru.archlabs.proto.common.UserState toProto(UserState user) {
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

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static StatusRuntimeException map(RuntimeException exception, Status.Code alreadyExists) {
        var message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        if (exception instanceof IllegalArgumentException) {
            if (message.startsWith("Unknown user")) {
                return Status.NOT_FOUND.withDescription(message).withCause(exception).asRuntimeException();
            }
            return Status.INVALID_ARGUMENT.withDescription(message).withCause(exception).asRuntimeException();
        }
        if (exception instanceof IllegalStateException) {
            return Status.fromCode(alreadyExists).withDescription(message).withCause(exception).asRuntimeException();
        }
        return Status.INTERNAL.withDescription(message).withCause(exception).asRuntimeException();
    }
}
