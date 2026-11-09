package ru.archlabs.chat.storage;

import ru.archlabs.chat.metrics.MetricsHttpServer;
import ru.archlabs.chat.service.ChatServerImpl;

/**
 * Lab 2 demo. The H2 URL can be overridden with ARCHLABS_DB_URL.
 */
public final class PersistentChatDemo {
    private PersistentChatDemo() {
    }

    public static void main(String[] args) throws Exception {
        var url =
                System.getenv()
                        .getOrDefault(
                                "ARCHLABS_DB_URL",
                                "jdbc:h2:file:./data/chat;DB_CLOSE_ON_EXIT=FALSE");
        try (var server = new ChatServerImpl(new JdbcChatStorage(url));
                var metrics = new MetricsHttpServer(server.metrics(), 9101)) {
            metrics.start();
            var alice = server.connect("alice");
            alice.setNotificationsEnabled(true);
            alice.close();

            var bob = server.connect("bob");
            bob.send("hello from the previous run");
            bob.close();

            var inbox = new java.util.concurrent.LinkedBlockingQueue<ru.archlabs.chat.domain.ChatMessage>();
            var reconnect = server.connect("alice");
            reconnect.addListener(inbox::add);
            var pending = inbox.poll(1, java.util.concurrent.TimeUnit.SECONDS);
            System.out.println("pending notification: " + pending);
            System.out.println("history: " + server.history(20));
            System.out.println("metrics: http://localhost:" + metrics.port() + "/metrics");
        }
    }
}
