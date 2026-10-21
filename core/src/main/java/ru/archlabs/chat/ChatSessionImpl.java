package ru.archlabs.chat;

import java.util.List;

/** Клиентская сессия доменного слоя. Вынесена из ChatServerImpl (без вложенных классов). */
final class ChatSessionImpl implements ChatSession {
    private final ChatServerImpl server;
    private final String username;
    private boolean closed;

    ChatSessionImpl(ChatServerImpl server, String username) {
        this.server = server;
        this.username = username;
    }

    String username() {
        return username;
    }

    @Override
    public Message send(String text) {
        requireOpen();
        return server.send(this, text);
    }

    @Override
    public void setNotificationsEnabled(boolean enabled) {
        requireOpen();
        server.setNotificationsEnabled(this, enabled);
    }

    @Override
    public List<Message> history() {
        requireOpen();
        return server.history(this);
    }

    @Override
    public void close() {
        if (!closed) {
            server.disconnect(this);
            closed = true;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Session is closed");
        }
    }
}
