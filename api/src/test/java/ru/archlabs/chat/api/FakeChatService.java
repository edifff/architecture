package ru.archlabs.chat.api;

import com.google.protobuf.Timestamp;
import io.grpc.stub.StreamObserver;
import ru.archlabs.proto.chat.ChatServiceGrpc;
import ru.archlabs.proto.chat.ConnectRequest;
import ru.archlabs.proto.chat.ConnectResponse;
import ru.archlabs.proto.chat.DisconnectRequest;
import ru.archlabs.proto.chat.HistoryRequest;
import ru.archlabs.proto.chat.MessagesResponse;
import ru.archlabs.proto.chat.SendRequest;
import ru.archlabs.proto.chat.SetNotificationsEnabledRequest;
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

    @Override
    public void connect(ConnectRequest request, StreamObserver<ConnectResponse> observer) {
        lastConnect = request;
        observer.onNext(ConnectResponse.newBuilder().setUser(user).build());
        observer.onCompleted();
    }

    @Override
    public void disconnect(DisconnectRequest request, StreamObserver<Empty> observer) {
        lastDisconnect = request;
        observer.onNext(Empty.getDefaultInstance());
        observer.onCompleted();
    }

    @Override
    public void send(SendRequest request, StreamObserver<Message> observer) {
        lastSend = request;
        observer.onNext(message(7, request.getText()));
        observer.onCompleted();
    }

    @Override
    public void history(HistoryRequest request, StreamObserver<MessagesResponse> observer) {
        observer.onNext(history);
        observer.onCompleted();
    }

    @Override
    public void setNotificationsEnabled(
            SetNotificationsEnabledRequest request, StreamObserver<Empty> observer) {
        lastNotifications = request;
        observer.onNext(Empty.getDefaultInstance());
        observer.onCompleted();
    }

    @Override
    public void user(UserRequest request, StreamObserver<UserState> observer) {
        if (!request.getName().equals("ivan")) {
            observer.onError(
                    io.grpc.Status.NOT_FOUND
                            .withDescription("Unknown user: " + request.getName())
                            .asRuntimeException());
            return;
        }
        observer.onNext(user);
        observer.onCompleted();
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
