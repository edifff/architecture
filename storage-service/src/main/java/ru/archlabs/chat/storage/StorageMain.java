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
        int shardIndex = intEnv("SHARD_INDEX", 0);
        int shardCount = intEnv("SHARD_COUNT", 1);
        String shardId = System.getenv().getOrDefault("SHARD_ID", "storage-" + shardIndex);
        String dbUrl =
                System.getenv()
                        .getOrDefault(
                                "ARCHLABS_DB_URL",
                                "jdbc:h2:file:/data/chat-" + shardIndex
                                        + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0");

        JdbcChatStorage storage = new JdbcChatStorage(dbUrl, shardIndex, shardCount);
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

        String label = "storage " + shardId + " ";
        System.out.println(label + "gRPC: localhost:" + port);
        System.out.println(label + "metrics: http://localhost:" + metricsPort + "/metrics");
        System.out.println(label + "database: " + dbUrl);
        server.awaitTermination();
    }

    private static int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }
}
