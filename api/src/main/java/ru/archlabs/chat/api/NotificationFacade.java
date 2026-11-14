package ru.archlabs.chat.api;

import java.util.function.Consumer;

import ru.archlabs.chat.domain.Message;

public final class NotificationFacade {
    private final ChatGateway gateway;

    NotificationFacade(ChatGateway gateway) {
        this.gateway = gateway;
    }

    public void setEnabled(String user, String body) {
        NotificationsRequest request = ChatJson.read(body, NotificationsRequest.class);
        gateway.setNotificationsEnabled(FacadeSupport.requireUser(user), request.enabled());
    }

    public AutoCloseable subscribe(String user, Consumer<Message> listener) {
        return gateway.subscribe(FacadeSupport.requireUser(user), listener);
    }
}
