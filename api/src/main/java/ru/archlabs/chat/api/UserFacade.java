package ru.archlabs.chat.api;

import ru.archlabs.chat.domain.ChatUser;

public final class UserFacade {
    private final ChatGateway gateway;

    UserFacade(ChatGateway gateway) {
        this.gateway = gateway;
    }

    public ChatUser find(String user) {
        return gateway.user(FacadeSupport.requireUser(user));
    }
}
