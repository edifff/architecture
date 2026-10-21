package ru.archlabs.chat.chat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import ru.archlabs.chat.ChatServerImpl;
import ru.archlabs.chat.metrics.MetricsServer;
import ru.archlabs.chat.metrics.RpcMetrics;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class ChatMain {
    private ChatMain() {
    }

    public static void main(String[] args) throws Exception {
        var port = Integer.parseInt(env("CHAT_GRPC_PORT", "50052"));
        var storageHost = env("STORAGE_HOST", "localhost");
        var storagePort = Integer.parseInt(env("STORAGE_GRPC_PORT", "50051"));

        ManagedChannel storageChannel =
                ManagedChannelBuilder.forAddress(storageHost, storagePort).usePlaintext().build();
        var storage = new GrpcChatStorage(storageChannel);

        resetOnlineUsersWithRetries(storage);

        var chat = new ChatServerImpl(storage);
        var rpcMetrics = new RpcMetrics();
        var metricsPort = Integer.parseInt(env("METRICS_PORT", "9101"));
        Server server =
                ServerBuilder.forPort(port)
                        .addService(
                                ServerInterceptors.intercept(
                                        new ChatGrpcService(chat), new MetricsInterceptor(rpcMetrics)))
                        .build()
                        .start();
        var metricsServer =
                new MetricsServer(
                        metricsPort, () -> chat.metrics().scrape() + rpcMetrics.scrape());
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    metricsServer.close();
                                    server.shutdown();
                                    chat.close();
                                    storageChannel.shutdown();
                                },
                                "chat-shutdown"));
        System.out.println("Chat gRPC server is listening on " + port);
        new CountDownLatch(1).await();
        storageChannel.awaitTermination(5, TimeUnit.SECONDS);
    }

    private static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void resetOnlineUsersWithRetries(GrpcChatStorage storage)
            throws InterruptedException {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                storage.resetOnlineUsers();
                return;
            } catch (RuntimeException exception) {
                lastFailure = exception;
                Thread.sleep(1000);
            }
        }
        throw lastFailure;
    }
}
