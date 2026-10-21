package ru.archlabs.chat.api;

import io.grpc.ManagedChannel;
import ru.archlabs.proto.chat.ChatServiceGrpc;
import ru.archlabs.proto.chat.SetNotificationsEnabledRequest;
import ru.archlabs.proto.chat.SubscribeRequest;
import ru.archlabs.proto.common.Message;

import java.util.function.Consumer;

public final class NotificationFacade {
    private final ChatServiceGrpc.ChatServiceBlockingStub blocking;

    public NotificationFacade(ManagedChannel channel) {
        this.blocking = ChatServiceGrpc.newBlockingStub(channel);
    }

    public void setNotificationsEnabled(String username, String body) {
        var request = ChatJson.read(body, NotificationsRequest.class);
        FacadeSupport.requireUsername(username);
        blocking.setNotificationsEnabled(
                SetNotificationsEnabledRequest.newBuilder()
                        .setName(username)
                        .setEnabled(request.enabled())
                        .build());
    }

    public Subscription subscribe(String username, Consumer<Message> onMessage) {
        FacadeSupport.requireUsername(username);
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
}
