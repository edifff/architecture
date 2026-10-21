package ru.archlabs.chat.chat;

import ru.archlabs.chat.Message;
import ru.archlabs.chat.UserState;
import ru.archlabs.chat.storage.ChatStorage;
import ru.archlabs.chat.storage.StorageException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Хранилище, которое всегда падает: проверяет маппинг сбоев в gRPC INTERNAL. */
final class FailingChatStorage implements ChatStorage {
    private static StorageException failure() {
        return new StorageException("storage down");
    }

    @Override
    public void connect(String name, Instant at) {
        throw failure();
    }

    @Override
    public void disconnect(String name, Instant at) {
        throw failure();
    }

    @Override
    public void setNotificationsEnabled(String name, boolean enabled) {
        throw failure();
    }

    @Override
    public Message append(String sender, String text, Instant sentAt) {
        throw failure();
    }

    @Override
    public List<Message> undeliveredNotifications(String recipient) {
        throw failure();
    }

    @Override
    public void clearNotifications(String recipient, List<Long> messageIds) {
        throw failure();
    }

    @Override
    public List<Message> history() {
        throw failure();
    }

    @Override
    public Optional<UserState> user(String name) {
        throw failure();
    }

    @Override
    public List<UserState> onlineUsersWithNotificationsEnabled() {
        throw failure();
    }

    @Override
    public void resetOnlineUsers() {
        throw failure();
    }
}
