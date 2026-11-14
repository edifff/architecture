package ru.archlabs.chat.storage;

import io.grpc.Server;
import io.grpc.ServerBuilder;

import ru.archlabs.chat.metrics.ChatMetrics;
import ru.archlabs.chat.metrics.MetricsHttpServer;

/**
 * Storage layer container: JDBC/H2 behind the storage gRPC contract.
 */
public final class StorageMain {
    private StorageMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = intEnv("STORAGE_PORT", 9092);
        int metricsPort = intEnv("STORAGE_METRICS_PORT", 9102);
        String dbUrl =
                System.getenv()
                        .getOrDefault(
                                "ARCHLABS_DB_URL",
                                "jdbc:h2:file:/data/chat;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0");

        JdbcChatStorage storage = new JdbcChatStorage(dbUrl);
        storage.initialize();
        storage.resetOnlineUsers();
        ChatMetrics metrics = new ChatMetrics();
        Server server =
                ServerBuilder.forPort(port)
                        .addService(new StorageGrpcService(storage, metrics))
                        .build()
                        .start();
        MetricsHttpServer metricsServer = new MetricsHttpServer(metrics, metricsPort);
        metricsServer.start();
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    server.shutdownNow();
                                    metricsServer.close();
                                    storage.close();
                                }));

        System.out.println("storage gRPC: localhost:" + port);
        System.out.println("storage metrics: http://localhost:" + metricsPort + "/metrics");
        server.awaitTermination();
    }

    private static int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }
}
