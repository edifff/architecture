package ru.archlabs.chat.storage;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import ru.archlabs.chat.ChatSession;
import ru.archlabs.chat.domain.Message;
import ru.archlabs.chat.metrics.MetricsHttpServer;
import ru.archlabs.chat.service.ChatServerImpl;

/**
 *  The H2 URL can be overridden with ARCHLABS_DB_URL.
 */
public final class PersistentChatDemo {
    private static final String ALICE = "alice";

    private PersistentChatDemo() {
    }

    public static void main(String[] args) throws Exception {
        String url =
                System.getenv()
                        .getOrDefault(
                                "ARCHLABS_DB_URL",
                                "jdbc:h2:file:./data/chat;DB_CLOSE_ON_EXIT=FALSE");
        try (ChatServerImpl server = new ChatServerImpl(new JdbcChatStorage(url));
                MetricsHttpServer metrics = new MetricsHttpServer(server.metrics(), 9101)) {
            metrics.start();
            ChatSession alice = server.connect(ALICE);
            alice.setNotificationsEnabled(true);
            alice.close();

            ChatSession bob = server.connect("bob");
            bob.send("hello from the previous run");
            bob.close();

            LinkedBlockingQueue<Message> inbox = new LinkedBlockingQueue<Message>();
            ChatSession reconnect = server.connect(ALICE);
            reconnect.addListener(inbox::add);
            Message pending = inbox.poll(1, TimeUnit.SECONDS);
            System.out.println("pending notification: " + pending);
            System.out.println("history: " + server.history(20));
            System.out.println("metrics: http://localhost:" + metrics.port() + "/metrics");
        }
    }
}
