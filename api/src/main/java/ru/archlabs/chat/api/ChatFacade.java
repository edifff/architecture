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
 * Входной элемент: разбирает пользовательское сообщение, проверяет его и конвертирует
 * в protobuf-запросы chat-сервиса. Не знает ни о HTTP-маршрутах, ни о статусах ответа —
 * это забота транспорта.
 */
public final class ChatFacade {
    private final ChatServiceGrpc.ChatServiceBlockingStub blocking;

    public ChatFacade(ManagedChannel channel) {
        this.blocking = ChatServiceGrpc.newBlockingStub(channel);
    }

    public UserState connect(String username) {
        requireUsername(username);
        return blocking.connect(ConnectRequest.newBuilder().setName(username).build()).getUser();
    }

    public void disconnect(String username) {
        requireUsername(username);
        blocking.disconnect(DisconnectRequest.newBuilder().setName(username).build());
    }

    /** Разбирает тело пользовательского сообщения, проверяет текст и отправляет его. */
    public Message send(String username, String body) {
        var request = ChatJson.read(body, SendMessageRequest.class);
        requireUsername(username);
        requireText(request.text());
        return blocking.send(
                SendRequest.newBuilder().setName(username).setText(request.text()).build());
    }

    public List<Message> history(String username) {
        requireUsername(username);
        return blocking.history(HistoryRequest.newBuilder().setName(username).build())
                .getMessagesList();
    }

    /** Разбирает тело запроса настроек и конвертирует его в protobuf. */
    public void setNotificationsEnabled(String username, String body) {
        var request = ChatJson.read(body, NotificationsRequest.class);
        requireUsername(username);
        blocking.setNotificationsEnabled(
                SetNotificationsEnabledRequest.newBuilder()
                        .setName(username)
                        .setEnabled(request.enabled())
                        .build());
    }

    public UserState user(String username) {
        requireUsername(username);
        return blocking.user(UserRequest.newBuilder().setName(username).build());
    }

    public Subscription subscribe(String username, Consumer<Message> onMessage) {
        requireUsername(username);
        var thread =
                new Thread(
                        () -> {
                            var stream =
                                    blocking.subscribe(
                                            SubscribeRequest.newBuilder().setName(username).build());
                            try {
                                while (stream.hasNext()) {
                                    onMessage.accept(stream.next());
                                }
                            } catch (RuntimeException ignored) {
                            }
                        },
                        "facade-subscribe-" + username);
        thread.setDaemon(true);
        thread.start();
        return new Subscription(thread);
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
