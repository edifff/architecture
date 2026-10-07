package ru.archlabs.chat.http;

import ru.archlabs.chat.ChatSession;
import ru.archlabs.chat.Message;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Клиентская сессия поверх HTTP. Оповещения приходят по отдельному SSE-потоку
 * и передаются в переданный обработчик {@code onMessage}.
 */
final class HttpChatSession implements ChatSession {
    private final HttpChatClient client;
    private final String username;
    private final ExecutorService events;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Stream<String> eventStream;

    HttpChatSession(HttpChatClient client, String username, Consumer<Message> onMessage) {
        this.client = client;
        this.username = username;
        this.events = Executors.newSingleThreadExecutor(runnable -> {
                            Thread thread = new Thread(runnable, "chat-events-" + username);
                            thread.setDaemon(true);
                            return thread;});
        startEvents(onMessage);
    }

    @Override
    public Message send(String text) {
        ensureOpen();
        HttpResponse<String> response = client.post(path("/messages/"), new ChatProtocol.SendMessageRequest(text));
        client.expect(response, 201, "send message");
        return ChatJson.read(response.body(), ChatProtocol.MessagePayload.class).toMessage();
    }

    @Override
    public void setNotificationsEnabled(boolean enabled) {
        ensureOpen();
        HttpResponse<String> response = client.put(path("/notifications/"), new ChatProtocol.NotificationsRequest(enabled));
        client.expect(response, 204, "set notifications");
    }

    @Override
    public List<Message> history() {
        ensureOpen();
        HttpResponse<String> response = client.get(path("/messages/"));
        client.expect(response, 200, "read history");
        return ChatJson.readList(response.body(), ChatProtocol.MessagePayload.class).stream()
                .map(ChatProtocol.MessagePayload::toMessage)
                .toList();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            client.sessions().remove(this);
            try {
                client.delete(path("/sessions/"));
            } catch (RuntimeException ignored) {
                // сервер уже мог быть остановлен
            }
            var stream = eventStream;
            if (stream != null) {
                stream.close();
            }
            events.shutdownNow();
        }
    }

    private void startEvents(Consumer<Message> onMessage) {
        HttpRequest request = HttpRequest.newBuilder(client.resolve(path("/events/")))
                        .header("Accept", ChatProtocol.CONTENT_TYPE_EVENT_STREAM)
                        .GET()
                        .build();
        eventStream = client.sendStream(request).body();
        events.submit(() -> {
                    try {
                        eventStream.forEach(line -> {
                                    try {
                                        accept(line, onMessage);
                                    } catch (RuntimeException ignored) {
                                        // некорректное событие пропускаем
                                    }
                                });
                    } catch (RuntimeException ignored) {
                        // поток закрыт при отключении сессии
                    }
                });
    }

    private static void accept(String line, Consumer<Message> onMessage) {
        if (line.startsWith(ChatProtocol.DATA_PREFIX)) {
            String json = line.substring(ChatProtocol.DATA_PREFIX.length()).trim();
            onMessage.accept(
                    ChatJson.read(json, ChatProtocol.MessagePayload.class).toMessage());
        }
    }

    private String path(String prefix) {
        return prefix + HttpChatClient.encode(username);
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Session is closed");
        }
    }
}
