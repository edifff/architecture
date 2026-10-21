package ru.archlabs.chat.storage;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import ru.archlabs.chat.metrics.MetricsServer;
import ru.archlabs.chat.metrics.RpcMetrics;

import java.util.concurrent.CountDownLatch;

/** Точка входа слоя хранения: gRPC-сервер поверх JDBC-хранилища PostgreSQL. */
public final class StorageMain {
    private StorageMain() {
    }

    public static void main(String[] args) throws Exception {
        var dbUrl = env("CHAT_DB_URL", "jdbc:postgresql://localhost:5432/chat");
        var dbUser = env("CHAT_DB_USER", "chat");
        var dbPassword = env("CHAT_DB_PASSWORD", "chat");
        var port = Integer.parseInt(env("STORAGE_GRPC_PORT", "50051"));

        var storage = new PostgresChatStorage(dbUrl, dbUser, dbPassword);
        var rpcMetrics = new RpcMetrics();
        var metricsPort = Integer.parseInt(env("METRICS_PORT", "9102"));
        Server server =
                ServerBuilder.forPort(port)
                        .addService(
                                ServerInterceptors.intercept(
                                        new StorageGrpcService(storage), new MetricsInterceptor(rpcMetrics)))
                        .build()
                        .start();
        var metricsServer = new MetricsServer(metricsPort, rpcMetrics::scrape);
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    metricsServer.close();
                                    server.shutdown();
                                    storage.close();
                                },
                                "storage-shutdown"));
        System.out.println("Storage gRPC server is listening on " + port);
        new CountDownLatch(1).await();
    }

    private static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
