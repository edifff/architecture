package ru.archlabs.chat.http;

import ru.archlabs.chat.ChatSession;
import ru.archlabs.chat.Message;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Состояние одного HTTP-клиента на сервере: доменная сессия чата и очередь
 * сообщений, которую вычитывает и отправляет клиенту SSE-сервлет.
 */
final class ClientConnection {
    private static final int CAPACITY = 10_000;

    private final String username;
    private final BlockingQueue<Message> pending = new LinkedBlockingQueue<>(CAPACITY);
    private ChatSession session;

    ClientConnection(String username) {
        this.username = username;
    }

    String username() {
        return username;
    }

    BlockingQueue<Message> pending() {
        return pending;
    }

    ChatSession session() {
        return session;
    }

    void session(ChatSession session) {
        this.session = session;
    }
}
