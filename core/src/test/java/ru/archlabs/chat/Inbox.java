package ru.archlabs.chat;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Очередь входящих сообщений для тестов домена. */
final class Inbox {
    private final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();

    void accept(Message message) {
        messages.add(message);
    }

    Message await() throws InterruptedException {
        return messages.poll(5, TimeUnit.SECONDS);
    }

    Message poll() throws InterruptedException {
        return messages.poll(300, TimeUnit.MILLISECONDS);
    }
}
