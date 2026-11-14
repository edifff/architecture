package ru.archlabs.chat.chat;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import ru.archlabs.chat.domain.ChatConflictException;
import ru.archlabs.chat.domain.ChatNotFoundException;
import ru.archlabs.chat.domain.ChatUser;
import ru.archlabs.chat.domain.ChatValidationException;
import ru.archlabs.chat.domain.Message;
import ru.archlabs.chat.domain.Notification;
import ru.archlabs.chat.storage.ChatStorage;
import ru.archlabs.chat.storage.StorageException;
import ru.archlabs.proto.common.ChatMessage;
import ru.archlabs.proto.common.ChatNotification;
import ru.archlabs.proto.common.Empty;
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
 * Domain-side adapter for the storage layer. No JDBC or H2 classes leak here.
 */
public final class GrpcChatStorage implements ChatStorage {
    private final ManagedChannel channel;
    private final StorageServiceGrpc.StorageServiceBlockingStub stub;

    public GrpcChatStorage(ManagedChannel channel) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.stub = StorageServiceGrpc.newBlockingStub(channel);
    }

    @Override
    public void initialize() {
    }

    @Override
    public void connect(String name, Instant at) {
        call(
                () ->
                        stub.connect(
                                ConnectRequest.newBuilder()
                                        .setName(name)
                                        .setAtEpochMillis(at.toEpochMilli())
                                        .build()));
    }

    @Override
    public void disconnect(String name, Instant at) {
        call(
                () ->
                        stub.disconnect(
                                ConnectRequest.newBuilder()
                                        .setName(name)
                                        .setAtEpochMillis(at.toEpochMilli())
                                        .build()));
    }

    @Override
    public void setNotificationsEnabled(String name, boolean enabled) {
        call(
                () ->
                        stub.setNotifications(
                                SetNotificationsRequest.newBuilder()
                                        .setName(name)
                                        .setEnabled(enabled)
                                        .build()));
    }

    @Override
    public Optional<ChatUser> findUser(String name) {
        try {
            return Optional.of(
                    ChatProtoMapper.fromProto(
                            stub.findUser(UserRequest.newBuilder().setName(name).build())));
        } catch (StatusRuntimeException exception) {
            if (exception.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return Optional.empty();
            }
            throw map(exception);
        }
    }

    @Override
    public List<ChatUser> allUsers() {
        UsersResponse response = call(() -> stub.listUsers(Empty.getDefaultInstance()));
        return response.getUsersList().stream().map(ChatProtoMapper::fromProto).toList();
    }

    @Override
    public Message appendMessage(
            String sender, String text, List<String> notificationRecipients, Instant at) {
        ChatMessage response =
                call(
                        () ->
                                stub.appendMessage(
                                        AppendMessageRequest.newBuilder()
                                                .setSender(sender)
                                                .setText(text)
                                                .addAllRecipients(notificationRecipients)
                                                .setAtEpochMillis(at.toEpochMilli())
                                                .build()));
        return ChatProtoMapper.fromProto(response);
    }

    @Override
    public List<Message> history(int limit) {
        HistoryResponse response =
                call(
                        () ->
                                stub.history(
                                        HistoryRequest.newBuilder().setLimit(limit).build()));
        return response.getMessagesList().stream().map(ChatProtoMapper::fromProto).toList();
    }

    @Override
    public Notification enqueueNotification(String recipient, Message message, Instant at) {
        ChatNotification response =
                call(
                        () ->
                                stub.enqueueNotification(
                                        EnqueueNotificationRequest.newBuilder()
                                                .setRecipient(recipient)
                                                .setMessage(ChatProtoMapper.toProto(message))
                                                .setAtEpochMillis(at.toEpochMilli())
                                                .build()));
        return ChatProtoMapper.fromProto(response);
    }

    @Override
    public List<Notification> pendingNotifications(String recipient) {
        NotificationsResponse response =
                call(
                        () ->
                                stub.pendingNotifications(
                                        UserRequest.newBuilder().setName(recipient).build()));
        return response.getNotificationsList().stream()
                .map(ChatProtoMapper::fromProto)
                .toList();
    }

    @Override
    public void markNotificationDelivered(long notificationId, Instant at) {
        call(
                () ->
                        stub.markNotificationDelivered(
                                MarkDeliveredRequest.newBuilder()
                                        .setId(notificationId)
                                        .setAtEpochMillis(at.toEpochMilli())
                                        .build()));
    }

    @Override
    public void resetOnlineUsers() {
        call(() -> stub.resetOnlineUsers(Empty.getDefaultInstance()));
    }

    @Override
    public void close() {
    }

    private static <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (StatusRuntimeException exception) {
            throw map(exception);
        }
    }

    private static RuntimeException map(StatusRuntimeException exception) {
        String description = exception.getStatus().getDescription();
        Status.Code code = exception.getStatus().getCode();
        return switch (code) {
            case INVALID_ARGUMENT -> new ChatValidationException(
                    description == null ? "Invalid storage request" : description);
            case NOT_FOUND -> new ChatNotFoundException(
                    description == null ? "Storage entity not found" : description);
            case ALREADY_EXISTS, FAILED_PRECONDITION -> new ChatConflictException(
                    description == null ? "Storage conflict" : description);
            default -> new StorageException("Storage RPC failed: " + code, exception);
        };
    }
}
