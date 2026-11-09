package ru.archlabs.chat.chat;

import io.grpc.ManagedChannelBuilder;
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
        var port = intEnv("CHAT_PORT", 9091);
        var metricsPort = intEnv("CHAT_METRICS_PORT", 9101);
        var storageHost = System.getenv().getOrDefault("STORAGE_HOST", "localhost");
        var storagePort = intEnv("STORAGE_PORT", 9092);

        var channel =
                ManagedChannelBuilder.forAddress(storageHost, storagePort).usePlaintext().build();
        var storage = new GrpcChatStorage(channel);
        var server = new ChatServerImpl(storage);
        var grpc =
                ServerBuilder.forPort(port)
                        .addService(new ChatGrpcService(server))
                        .build()
                        .start();
        var metricsServer = new MetricsHttpServer(server.metrics(), metricsPort);
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

        System.out.println("chat gRPC: localhost:" + port);
        System.out.println("chat metrics: http://localhost:" + metricsPort + "/metrics");
        System.out.println("storage target: " + storageHost + ":" + storagePort);
        grpc.awaitTermination();
    }

    private static int intEnv(String name, int fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }
}
