package ru.archlabs.chat;

import java.util.List;

import ru.archlabs.chat.domain.ChatUser;
import ru.archlabs.chat.domain.Message;
import ru.archlabs.chat.metrics.ChatMetrics;

/**
 * In-process chat façade used by tests and by the local API adapter.
 */
public interface ChatServer extends AutoCloseable {
    ChatSession connect(String userName);

    ChatUser user(String userName);

    List<Message> history(int limit);

    DeliverySummary deliver(Message message);

    ChatMetrics metrics();

    @Override
    void close();
}
