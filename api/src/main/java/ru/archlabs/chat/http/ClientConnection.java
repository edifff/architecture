package ru.archlabs.chat.http;

import ru.archlabs.chat.api.Subscription;
import ru.archlabs.proto.common.Message;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

final class ClientConnection {
    private static final int CAPACITY = 10_000;

    private final String username;
    private final BlockingQueue<Message> pending = new LinkedBlockingQueue<>(CAPACITY);
    private Subscription subscription;

    ClientConnection(String username) {
        this.username = username;
    }

    String username() {
        return username;
    }

    BlockingQueue<Message> pending() {
        return pending;
    }

    Subscription subscription() {
        return subscription;
    }

    void subscription(Subscription subscription) {
        this.subscription = subscription;
    }
}
