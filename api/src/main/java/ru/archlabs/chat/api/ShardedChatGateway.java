package ru.archlabs.chat.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;

import ru.archlabs.chat.domain.ChatInternalException;
import ru.archlabs.chat.domain.ChatUser;
import ru.archlabs.chat.domain.Message;
import ru.archlabs.chat.metrics.ChatMetrics;
import ru.archlabs.proto.chat.ConnectRequest;
import ru.archlabs.proto.chat.DeliverRequest;
import ru.archlabs.proto.chat.DeliveryReport;
import ru.archlabs.proto.chat.HistoryRequest;
import ru.archlabs.proto.chat.HistoryResponse;
import ru.archlabs.proto.chat.LocalHistoryRequest;
import ru.archlabs.proto.chat.SendMessageRequest;
import ru.archlabs.proto.chat.SetNotificationsRequest;
import ru.archlabs.proto.chat.StatusSendResponse;
import ru.archlabs.proto.chat.UserRequest;
import ru.archlabs.proto.common.ChatMessage;
import ru.archlabs.proto.common.UserState;

/**
 * The input element of the sharded system. It owns the deterministic entity-to-shard
 * mapping, routes single-entity calls to one computational shard, and scatters general
 * operations over every shard before combining their reports.
 */
public final class ShardedChatGateway implements ChatGateway {
    private final List<ChatShard> shards;
    private final ShardRouter router;
    private final ExecutorService fanOut;
    private final ChatMetrics metrics = new ChatMetrics();

    public ShardedChatGateway(List<ChatShard> shards) {
        this(shards, Executors.newVirtualThreadPerTaskExecutor());
    }

    ShardedChatGateway(List<ChatShard> shards, ExecutorService fanOut) {
        if (shards == null || shards.isEmpty()) {
            throw new IllegalArgumentException("At least one chat shard is required");
        }
        this.shards = List.copyOf(shards);
        this.fanOut = Objects.requireNonNull(fanOut, "fanOut");
        router = new ShardRouter(this.shards.size());
        Set<String> ids = new HashSet<String>();
        for (ChatShard shard : this.shards) {
            if (!ids.add(shard.id())) {
                throw new IllegalArgumentException("Duplicate shard id: " + shard.id());
            }
        }
    }

    public static ShardedChatGateway fromSpec(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("Shard list must not be blank");
        }
        ArrayList<ChatShard> shards = new ArrayList<ChatShard>();
        String[] entries = spec.split(",");
        for (String rawEntry : entries) {
            String entry = rawEntry.strip();
            if (!entry.isEmpty()) {
                shards.add(openShard(entry));
            }
        }
        return new ShardedChatGateway(shards);
    }

    @Override
    public ChatUser connect(String user) {
        metrics.requestProcessed("api_connect");
        ChatShard shard = shardFor(user);
        return map(call(() -> shard.blocking().connect(request(user))));
    }

    @Override
    public void disconnect(String user) {
        metrics.requestProcessed("api_disconnect");
        ChatShard shard = shardFor(user);
        run(() -> shard.blocking().disconnect(userRequest(user)));
    }

    @Override
    public Message send(String user, String text) {
        metrics.requestProcessed("api_send");
        ChatShard primary = shardFor(user);
        ChatMessage message =
                call(
                        () ->
                                primary.blocking()
                                        .send(
                                                SendMessageRequest.newBuilder()
                                                        .setName(user)
                                                        .setText(text)
                                                        .build()));
        fanOutDelivery(primary, message);
        return map(message);
    }

    @Override
    public List<Message> history(String user, int limit) {
        metrics.requestProcessed("api_history");
        ChatShard primary = shardFor(user);
        run(
                () ->
                        primary.blocking()
                                .history(
                                        HistoryRequest.newBuilder()
                                                .setName(user)
                                                .setLimit(1)
                                                .build()));
        LocalHistoryRequest request =
                LocalHistoryRequest.newBuilder().setLimit(limit).build();
        List<HistoryResponse> parts =
                fanOut(shards, shard -> shard.blocking().localHistory(request));
        ArrayList<ChatMessage> merged = new ArrayList<ChatMessage>();
        for (HistoryResponse part : parts) {
            merged.addAll(part.getMessagesList());
        }
        merged.sort(
                Comparator.comparingLong(ChatMessage::getCreatedAtEpochMillis)
                        .thenComparingLong(ChatMessage::getId));
        int from = Math.max(0, merged.size() - limit);
        return merged.subList(from, merged.size()).stream()
                .map(ShardedChatGateway::map)
                .toList();
    }

    @Override
    public void setNotificationsEnabled(String user, boolean enabled) {
        metrics.requestProcessed("api_set_notifications");
        ChatShard shard = shardFor(user);
        run(
                () ->
                        shard.blocking()
                                .setNotifications(
                                        SetNotificationsRequest.newBuilder()
                                                .setName(user)
                                                .setEnabled(enabled)
                                                .build()));
    }

    @Override
    public ChatUser user(String user) {
        metrics.requestProcessed("api_get_user");
        ChatShard shard = shardFor(user);
        return map(call(() -> shard.blocking().getUser(userRequest(user))));
    }

    @Override
    public AutoCloseable subscribe(String user, Consumer<Message> listener) {
        metrics.requestProcessed("api_subscribe");
        ChatShard shard = shardFor(user);
        AtomicReference<ClientCallStreamObserver<UserRequest>> requestStream =
                new AtomicReference<ClientCallStreamObserver<UserRequest>>();
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
        shard.async().subscribe(userRequest(user), observer);
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
        ChatShard primary = shardFor(user);
        StatusSendResponse response =
                call(
                        () ->
                                primary.blocking()
                                        .sendStatus(
                                                SendMessageRequest.newBuilder()
                                                        .setName(user)
                                                        .setText(text)
                                                        .build()));
        ArrayList<ShardReport> reports = new ArrayList<ShardReport>();
        reports.add(report(response.getDelivery()));
        List<ChatShard> targets = otherShards(primary);
        if (!targets.isEmpty()) {
            DeliverRequest deliver =
                    DeliverRequest.newBuilder().setMessage(response.getMessage()).build();
            for (DeliveryReport delivery :
                    fanOut(targets, shard -> shard.blocking().deliver(deliver))) {
                reports.add(report(delivery));
            }
        }
        return aggregate(user, response.getMessage(), reports);
    }

    @Override
    public ShardAssignment shardAssignment(String user) {
        return new ShardAssignment(user, shardFor(user).id(), shards.size());
    }

    @Override
    public ChatMetrics metrics() {
        return metrics;
    }

    public List<String> shardIds() {
        return shards.stream().map(ChatShard::id).toList();
    }

    @Override
    public void close() {
        fanOut.shutdownNow();
        for (ChatShard shard : shards) {
            shard.close();
        }
    }

    private void fanOutDelivery(ChatShard origin, ChatMessage message) {
        List<ChatShard> targets = otherShards(origin);
        if (targets.isEmpty()) {
            return;
        }
        metrics.requestProcessed("api_deliver");
        DeliverRequest deliver = DeliverRequest.newBuilder().setMessage(message).build();
        fanOut(targets, shard -> shard.blocking().deliver(deliver));
    }

    private <T> List<T> fanOut(List<ChatShard> targets, Function<ChatShard, T> request) {
        List<CompletableFuture<T>> futures =
                targets.stream()
                        .map(shard -> CompletableFuture.supplyAsync(() -> request.apply(shard), fanOut))
                        .toList();
        CompletableFuture<?>[] pending = futures.toArray(new CompletableFuture<?>[0]);
        try {
            CompletableFuture.allOf(pending).join();
        } catch (CompletionException exception) {
            throw unwrap(exception);
        }
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private static RuntimeException unwrap(CompletionException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof StatusRuntimeException status) {
            return GrpcErrors.map(status);
        }
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        return new ChatInternalException("Shard request failed", cause);
    }

    private ChatShard shardFor(String user) {
        return shards.get(router.indexOf(user));
    }

    private List<ChatShard> otherShards(ChatShard origin) {
        return shards.stream().filter(shard -> !shard.id().equals(origin.id())).toList();
    }

    private static ChatShard openShard(String entry) {
        String address = entry;
        int at = entry.indexOf('@');
        String explicitId = null;
        if (at > 0) {
            explicitId = entry.substring(0, at).strip();
            address = entry.substring(at + 1).strip();
        }
        int colon = address.lastIndexOf(':');
        if (colon <= 0 || colon == address.length() - 1) {
            throw new IllegalArgumentException("Invalid shard target: " + entry);
        }
        String host = address.substring(0, colon).strip();
        int port = Integer.parseInt(address.substring(colon + 1).strip());
        String id = explicitId == null ? host : explicitId;
        return ChatShard.connect(id, host, port);
    }

    private static StatusReport aggregate(
            String user, ChatMessage message, List<ShardReport> reports) {
        int chats = 0;
        int delivered = 0;
        int queued = 0;
        for (ShardReport report : reports) {
            chats += report.chats();
            delivered += report.delivered();
            queued += report.queued();
        }
        return new StatusReport(
                user, map(message), reports.size(), chats, delivered, queued, reports);
    }

    private static ShardReport report(DeliveryReport report) {
        return new ShardReport(
                report.getShard(), report.getChats(), report.getDelivered(), report.getQueued());
    }

    private static ConnectRequest request(String user) {
        return ConnectRequest.newBuilder().setName(user).build();
    }

    private static UserRequest userRequest(String user) {
        return UserRequest.newBuilder().setName(user).build();
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

    private static <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (StatusRuntimeException exception) {
            throw GrpcErrors.map(exception);
        }
    }

    private static void run(Runnable action) {
        try {
            action.run();
        } catch (StatusRuntimeException exception) {
            throw GrpcErrors.map(exception);
        }
    }
}
