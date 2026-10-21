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

public final class ChatFacade {
    private final ChatServiceGrpc.ChatServiceBlockingStub blocking;

    public ChatFacade(ManagedChannel channel) {
        this.blocking = ChatServiceGrpc.newBlockingStub(channel);
    }

    public String handle(String userMessage) {
        var parsed = UserMessage.parse(userMessage);
        requireUsername(parsed.username());
        return switch (parsed.operation()) {
            case CONNECT -> connectText(parsed.username());
            case DISCONNECT -> disconnectText(parsed.username());
            case SEND -> sendText(parsed.username(), parsed.text());
            case HISTORY -> historyText(parsed.username());
            case SET_NOTIFICATIONS -> notificationsText(parsed.username(), parsed.enabled());
            case USER -> userText(parsed.username());
        };
    }

    private String connectText(String username) {
        var user = connect(username);
        return "connected " + user.getName() + " online=" + user.getOnline();
    }

    private String disconnectText(String username) {
        disconnect(username);
        return "disconnected " + username;
    }

    private String sendText(String username, String text) {
        var message = send(username, text);
        return "sent #"
                + message.getId()
                + " "
                + message.getSender()
                + ": "
                + message.getText();
    }

    private String historyText(String username) {
        return history(username).stream()
                .map(
                        message ->
                                "#"
                                        + message.getId()
                                        + " "
                                        + message.getSender()
                                        + ": "
                                        + message.getText())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String notificationsText(String username, boolean enabled) {
        setNotificationsEnabled(username, enabled);
        return "notifications " + enabled + " for " + username;
    }

    private String userText(String username) {
        var user = user(username);
        return "user "
                + user.getName()
                + " online="
                + user.getOnline()
                + " notifications="
                + user.getNotificationsEnabled();
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
