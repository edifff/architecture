package ru.archlabs.chat.api;

import ru.archlabs.chat.domain.ChatMessage;

import java.util.function.Consumer;

public final class NotificationFacade {
    private final ChatGateway gateway;

    NotificationFacade(ChatGateway gateway) {
        this.gateway = gateway;
    }

    public void setEnabled(String user, String body) {
        var request = ChatJson.read(body, NotificationsRequest.class);
        gateway.setNotificationsEnabled(FacadeSupport.requireUser(user), request.enabled());
    }

    public AutoCloseable subscribe(String user, Consumer<ChatMessage> listener) {
        return gateway.subscribe(FacadeSupport.requireUser(user), listener);
    }
}
