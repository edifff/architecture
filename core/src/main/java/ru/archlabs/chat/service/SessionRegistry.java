package ru.archlabs.chat.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Logical category: live sessions. Guarded by the owning server lock.
 */
final class SessionRegistry {
    private final Map<String, ChatSessionImpl> byUser = new HashMap<>();

    Optional<ChatSessionImpl> find(String userName) {
        return Optional.ofNullable(byUser.get(userName));
    }

    boolean contains(String userName) {
        return byUser.containsKey(userName);
    }

    void add(ChatSessionImpl session) {
        byUser.put(session.userName(), session);
    }

    boolean remove(ChatSessionImpl session) {
        return byUser.remove(session.userName(), session);
    }

    List<ChatSessionImpl> snapshot() {
        return List.copyOf(byUser.values());
    }

    int size() {
        return byUser.size();
    }

    void clear() {
        byUser.clear();
    }
}
