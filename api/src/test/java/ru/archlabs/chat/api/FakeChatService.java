package ru.archlabs.chat.api;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
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
import ru.archlabs.proto.common.Message;
import ru.archlabs.proto.common.UserState;

final class FakeChatService extends ChatServiceGrpc.ChatServiceImplBase {
    final UserState user =
            UserState.newBuilder()
                    .setName("ivan")
                    .setOnline(true)
                    .setNotificationsEnabled(true)
                    .setLastConnected(Timestamp.newBuilder().setSeconds(1).build())
                    .build();
    ConnectRequest lastConnect;
    SendRequest lastSend;
    DisconnectRequest lastDisconnect;
    SetNotificationsEnabledRequest lastNotifications;
    MessagesResponse history = MessagesResponse.getDefaultInstance();
    Status.Code failure;
    private StreamObserver<Message> subscriber;

    void push(Message message) {
        subscriber.onNext(message);
    }

    private boolean fail(StreamObserver<?> observer) {
        if (failure == null) {
            return false;
        }
        observer.onError(
                Status.fromCode(failure).withDescription("simulated failure").asRuntimeException());
        return true;
    }

    @Override
    public void connect(ConnectRequest request, StreamObserver<ConnectResponse> observer) {
        if (fail(observer)) {
            return;
        }
        lastConnect = request;
        observer.onNext(ConnectResponse.newBuilder().setUser(user).build());
        observer.onCompleted();
    }

    @Override
    public void disconnect(DisconnectRequest request, StreamObserver<Empty> observer) {
        if (fail(observer)) {
            return;
        }
        lastDisconnect = request;
        observer.onNext(Empty.getDefaultInstance());
        observer.onCompleted();
    }

    @Override
    public void send(SendRequest request, StreamObserver<Message> observer) {
        if (fail(observer)) {
            return;
        }
        lastSend = request;
        observer.onNext(message(7, request.getText()));
        observer.onCompleted();
    }

    @Override
    public void history(HistoryRequest request, StreamObserver<MessagesResponse> observer) {
        if (fail(observer)) {
            return;
        }
        observer.onNext(history);
        observer.onCompleted();
    }

    @Override
    public void setNotificationsEnabled(
            SetNotificationsEnabledRequest request, StreamObserver<Empty> observer) {
        if (fail(observer)) {
            return;
        }
        lastNotifications = request;
        observer.onNext(Empty.getDefaultInstance());
        observer.onCompleted();
    }

    @Override
    public void user(UserRequest request, StreamObserver<UserState> observer) {
        if (fail(observer)) {
            return;
        }
        if (!request.getName().equals("ivan")) {
            observer.onError(
                    Status.NOT_FOUND
                            .withDescription("Unknown user: " + request.getName())
                            .asRuntimeException());
            return;
        }
        observer.onNext(user);
        observer.onCompleted();
    }

    @Override
    public void subscribe(SubscribeRequest request, StreamObserver<Message> observer) {
        if (fail(observer)) {
            return;
        }
        subscriber = observer;
    }

    static Message message(long id, String text) {
        return Message.newBuilder()
                .setId(id)
                .setSender("ivan")
                .setText(text)
                .setSentAt(Timestamp.newBuilder().setSeconds(1).build())
                .build();
    }
}
