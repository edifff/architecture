package ru.archlabs.chat.demo;

import ru.archlabs.chat.metrics.MetricsHttpServer;
import ru.archlabs.chat.service.ChatServerImpl;

/**
 * Lab 1 demo: two users in one JVM, communicating through the Java interface only.
 */
public final class InMemoryChatDemo {
    private InMemoryChatDemo() {
    }

    public static void main(String[] args) throws Exception {
        try (var server = new ChatServerImpl();
                var metrics = new MetricsHttpServer(server.metrics(), 9100)) {
            metrics.start();
            var alice = server.connect("alice");
            alice.addListener(message -> System.out.println("alice <- " + message));
            var bob = server.connect("bob");
            bob.addListener(message -> System.out.println("bob <- " + message));

            bob.send("hello from bob");
            alice.send("hello from alice");

            System.out.println("history: " + alice.history(10));
            System.out.println("metrics: http://localhost:" + metrics.port() + "/metrics");
            Thread.sleep(500);
        }
    }
}
