package ru.archlabs.chat.chat;

import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import ru.archlabs.chat.Message;
import ru.archlabs.chat.UserState;
import ru.archlabs.chat.storage.ChatStorage;
import ru.archlabs.proto.common.Empty;
import ru.archlabs.proto.storage.AppendRequest;
import ru.archlabs.proto.storage.ChatStorageGrpc;
import ru.archlabs.proto.storage.ClearNotificationsRequest;
import ru.archlabs.proto.storage.ConnectRequest;
import ru.archlabs.proto.storage.DisconnectRequest;
import ru.archlabs.proto.storage.HistoryRequest;
import ru.archlabs.proto.storage.SetNotificationsEnabledRequest;
import ru.archlabs.proto.storage.UndeliveredNotificationsRequest;
import ru.archlabs.proto.storage.UserRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Адаптер доменного интерфейса {@link ChatStorage} к gRPC-сервису хранения.
 * Домен по-прежнему не знает о транспорте: {@code ChatServerImpl} работает с этим
 * объектом как с обычным хранилищем.
 */
public final class GrpcChatStorage implements ChatStorage {
    private final ChatStorageGrpc.ChatStorageBlockingStub stub;

    public GrpcChatStorage(ManagedChannel channel) {
        this.stub = ChatStorageGrpc.newBlockingStub(channel);
    }

    @Override
    public void connect(String name, Instant at) {
        stub.connect(ConnectRequest.newBuilder().setName(name).setAt(timestamp(at)).build());
    }

    @Override
    public void disconnect(String name, Instant at) {
        stub.disconnect(DisconnectRequest.newBuilder().setName(name).setAt(timestamp(at)).build());
    }

    @Override
    public void setNotificationsEnabled(String name, boolean enabled) {
        stub.setNotificationsEnabled(
                SetNotificationsEnabledRequest.newBuilder()
                        .setName(name)
                        .setEnabled(enabled)
                        .build());
    }

    @Override
    public Message append(String sender, String text, Instant sentAt) {
        var stored =
                stub.append(
                        AppendRequest.newBuilder()
                                .setSender(sender)
                                .setText(text)
                                .setSentAt(timestamp(sentAt))
                                .build());
        return fromProto(stored);
    }

    @Override
    public List<Message> undeliveredNotifications(String recipient) {
        return stub.undeliveredNotifications(
                        UndeliveredNotificationsRequest.newBuilder()
                                .setRecipient(recipient)
                                .build())
                .getMessagesList()
                .stream()
                .map(GrpcChatStorage::fromProto)
                .toList();
    }

    @Override
    public void clearNotifications(String recipient, List<Long> messageIds) {
        stub.clearNotifications(
                ClearNotificationsRequest.newBuilder()
                        .setRecipient(recipient)
                        .addAllMessageIds(messageIds)
                        .build());
    }

    @Override
    public List<Message> history() {
        return stub.history(HistoryRequest.getDefaultInstance()).getMessagesList().stream()
                .map(GrpcChatStorage::fromProto)
                .toList();
    }

    @Override
    public Optional<UserState> user(String name) {
        try {
            return Optional.of(
                    fromProto(stub.user(UserRequest.newBuilder().setName(name).build())));
        } catch (StatusRuntimeException exception) {
            if (exception.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return Optional.empty();
            }
            throw exception;
        }
    }

    @Override
    public List<UserState> onlineUsersWithNotificationsEnabled() {
        return stub.onlineUsersWithNotificationsEnabled(Empty.getDefaultInstance()).getUsersList()
                .stream()
                .map(GrpcChatStorage::fromProto)
                .toList();
    }

    @Override
    public void resetOnlineUsers() {
        stub.resetOnlineUsers(Empty.getDefaultInstance());
    }

    private static Message fromProto(ru.archlabs.proto.common.Message message) {
        return new Message(
                message.getId(),
                message.getSender(),
                message.getText(),
                instant(message.getSentAt()));
    }

    private static UserState fromProto(ru.archlabs.proto.common.UserState user) {
        return new UserState(
                user.getName(),
                user.getOnline(),
                user.hasLastConnected() ? instant(user.getLastConnected()) : null,
                user.getNotificationsEnabled());
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
}
