package ru.archlabs.chat.api;

public final class Subscription implements AutoCloseable {
    private final Thread thread;

    Subscription(Thread thread) {
        this.thread = thread;
    }

    @Override
    public void close() {
        thread.interrupt();
    }
}
