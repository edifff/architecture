package ru.archlabs.chat.api;

import java.util.Objects;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import ru.archlabs.proto.chat.ChatServiceGrpc;

/**
 * A client handle for one identical chat shard.
 */
public final class ChatShard implements AutoCloseable {
    private final String id;
    private final ManagedChannel channel;
    private final ChatServiceGrpc.ChatServiceBlockingStub blocking;
    private final ChatServiceGrpc.ChatServiceStub async;

    private ChatShard(String id, ManagedChannel channel) {
        this.id = Objects.requireNonNull(id, "id");
        this.channel = Objects.requireNonNull(channel, "channel");
        blocking = ChatServiceGrpc.newBlockingStub(channel);
        async = ChatServiceGrpc.newStub(channel);
    }

    public static ChatShard connect(String id, String host, int port) {
        return over(id, ManagedChannelBuilder.forAddress(host, port).usePlaintext().build());
    }

    public static ChatShard over(String id, ManagedChannel channel) {
        return new ChatShard(id, channel);
    }

    public String id() {
        return id;
    }

    public ChatServiceGrpc.ChatServiceBlockingStub blocking() {
        return blocking;
    }

    public ChatServiceGrpc.ChatServiceStub async() {
        return async;
    }

    @Override
    public void close() {
        channel.shutdownNow();
    }
}
