package ru.archlabs.chat.chat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import ru.archlabs.chat.metrics.MetricsHttpServer;
import ru.archlabs.chat.service.ChatServerImpl;

/**
 * Domain layer container: gRPC in, gRPC storage out.
 */
public final class ChatMain {
    private ChatMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = intEnv("CHAT_PORT", 9091);
        int metricsPort = intEnv("CHAT_METRICS_PORT", 9101);
        int shardIndex = intEnv("SHARD_INDEX", 0);
        String shardId = System.getenv().getOrDefault("SHARD_ID", "chat-" + shardIndex);
        String storageHost = System.getenv().getOrDefault("STORAGE_HOST", "localhost");
        int storagePort = intEnv("STORAGE_PORT", 9092);

        ManagedChannel channel =
                ManagedChannelBuilder.forAddress(storageHost, storagePort).usePlaintext().build();
        GrpcChatStorage storage = new GrpcChatStorage(channel);
        ChatServerImpl server = new ChatServerImpl(storage);
        Server grpc =
                ServerBuilder.forPort(port)
                        .addService(new ChatGrpcService(server, shardId))
                        .build()
                        .start();
        MetricsHttpServer metricsServer = new MetricsHttpServer(server.metrics(), metricsPort);
        metricsServer.start();
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    grpc.shutdownNow();
                                    metricsServer.close();
                                    server.close();
                                    channel.shutdownNow();
                                }));

        String label = "chat " + shardId + " ";
        System.out.println(label + "gRPC: localhost:" + port);
        System.out.println(label + "metrics: http://localhost:" + metricsPort + "/metrics");
        System.out.println(label + "storage target: " + storageHost + ":" + storagePort);
        grpc.awaitTermination();
    }

    private static int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }
}
