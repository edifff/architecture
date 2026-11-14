package ru.archlabs.chat.api;

import ru.archlabs.chat.domain.ChatUser;

public final class SessionFacade {
    private final ChatGateway gateway;

    SessionFacade(ChatGateway gateway) {
        this.gateway = gateway;
    }

    public ChatUser connect(String user) {
        return gateway.connect(FacadeSupport.requireUser(user));
    }

    public void disconnect(String user) {
        gateway.disconnect(FacadeSupport.requireUser(user));
    }
}
