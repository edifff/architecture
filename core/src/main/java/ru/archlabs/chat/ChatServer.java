package ru.archlabs.chat;

import ru.archlabs.chat.domain.ChatMessage;
import ru.archlabs.chat.domain.UserState;
import ru.archlabs.chat.metrics.ChatMetrics;

import java.util.List;

/**
 * In-process chat façade used by lab 1 and by the local (non-gRPC) runner.
 */
public interface ChatServer extends AutoCloseable {
    ChatSession connect(String userName);

    UserState user(String userName);

    List<ChatMessage> history(int limit);

    ChatMetrics metrics();

    @Override
    void close();
}
