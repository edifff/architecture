package ru.archlabs.chat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import ru.archlabs.chat.api.ChatFacade;
import ru.archlabs.chat.http.ChatHttpServer;

import java.util.concurrent.CountDownLatch;

/** Точка входа процесса API: Netty HTTP/SSE и gRPC-клиент к chat-сервису. */
public final class ChatApplication {
    private ChatApplication() {
    }

    public static void main(String[] args) throws Exception {
        var httpPort = Integer.parseInt(env("CHAT_HTTP_PORT", "8080"));
        var chatHost = env("CHAT_HOST", "localhost");
        var chatPort = Integer.parseInt(env("CHAT_GRPC_PORT", "50052"));

        ManagedChannel channel =
                ManagedChannelBuilder.forAddress(chatHost, chatPort).usePlaintext().build();
        var facade = new ChatFacade(channel);
        var server = new ChatHttpServer(facade, httpPort);
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    server.close();
                                    channel.shutdown();
                                },
                                "api-shutdown"));
        server.start();
        System.out.println("Chat HTTP API is listening on " + server.baseUri());
        new CountDownLatch(1).await();
    }

    private static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
