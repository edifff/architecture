package ru.archlabs.chat.api;

import io.grpc.ManagedChannel;
import ru.archlabs.proto.chat.ChatServiceGrpc;
import ru.archlabs.proto.chat.ConnectRequest;
import ru.archlabs.proto.chat.DisconnectRequest;
import ru.archlabs.proto.chat.HistoryRequest;
import ru.archlabs.proto.chat.SendRequest;
import ru.archlabs.proto.chat.SetNotificationsEnabledRequest;
import ru.archlabs.proto.chat.SubscribeRequest;
import ru.archlabs.proto.chat.UserRequest;
import ru.archlabs.proto.common.Message;
import ru.archlabs.proto.common.UserState;

import java.util.List;
import java.util.function.Consumer;

/**
 * Входной элемент слоистой архитектуры («Facade»).
 *
 * <p>Принимает пользовательское сообщение в текстовом виде, <b>разбирает</b> его
 * ({@link UserMessage#parse}), <b>проверяет</b> инварианты и <b>конвертирует</b>
 * в protobuf-запросы контракта {@code chat.ChatService}, которые уходят в
 * chat-сервис по gRPC. Это единственная точка, где внешний формат встречается
 * с внутренним форматом межконтейнерного взаимодействия.
 */
public final class ChatFacade {
    private final ChatServiceGrpc.ChatServiceBlockingStub blocking;

    public ChatFacade(ManagedChannel channel) {
        this.blocking = ChatServiceGrpc.newBlockingStub(channel);
    }

    /** Полный цикл входного элемента: разбор → проверка → конвертация → вызов. */
    public String handle(String userMessage) {
        var parsed = UserMessage.parse(userMessage);
        requireUsername(parsed.username());
        return switch (parsed.operation()) {
            case CONNECT -> {
                var user = connect(parsed.username());
                yield "connected " + user.getName() + " online=" + user.getOnline();
            }
            case DISCONNECT -> {
                disconnect(parsed.username());
                yield "disconnected " + parsed.username();
            }
            case SEND -> {
                var message = send(parsed.username(), parsed.text());
                yield "sent #" + message.getId() + " " + message.getSender() + ": " + message.getText();
            }
            case HISTORY -> {
                var history = history(parsed.username());
                yield history.stream()
                        .map(message -> "#" + message.getId() + " " + message.getSender() + ": " + message.getText())
                        .reduce((left, right) -> left + "\n" + right)
                        .orElse("");
            }
            case SET_NOTIFICATIONS -> {
                setNotificationsEnabled(parsed.username(), parsed.enabled());
                yield "notifications " + parsed.enabled() + " for " + parsed.username();
            }
            case USER -> {
                var user = user(parsed.username());
                yield "user " + user.getName() + " online=" + user.getOnline()
                        + " notifications=" + user.getNotificationsEnabled();
            }
        };
    }

    public UserState connect(String username) {
        requireUsername(username);
        return blocking.connect(ConnectRequest.newBuilder().setName(username).build()).getUser();
    }

    public void disconnect(String username) {
        requireUsername(username);
        blocking.disconnect(DisconnectRequest.newBuilder().setName(username).build());
    }

    public Message send(String username, String text) {
        requireUsername(username);
        requireText(text);
        return blocking.send(
                SendRequest.newBuilder().setName(username).setText(text).build());
    }

    public List<Message> history(String username) {
        requireUsername(username);
        return blocking.history(HistoryRequest.newBuilder().setName(username).build())
                .getMessagesList();
    }

    public void setNotificationsEnabled(String username, boolean enabled) {
        requireUsername(username);
        blocking.setNotificationsEnabled(
                SetNotificationsEnabledRequest.newBuilder()
                        .setName(username)
                        .setEnabled(enabled)
                        .build());
    }

    public UserState user(String username) {
        requireUsername(username);
        return blocking.user(UserRequest.newBuilder().setName(username).build());
    }

    /** Открывает серверный поток оповещений и передаёт их обработчику. */
    public void subscribe(String username, Consumer<Message> onMessage) {
        requireUsername(username);
        var thread =
                new Thread(
                        () -> {
                            var stream =
                                    blocking.subscribe(
                                            SubscribeRequest.newBuilder().setName(username).build());
                            while (stream.hasNext()) {
                                onMessage.accept(stream.next());
                            }
                        },
                        "facade-subscribe-" + username);
        thread.setDaemon(true);
        thread.start();
    }

    private static void requireUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username must not be blank");
        }
    }

    private static void requireText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Message must not be blank");
        }
    }
}
