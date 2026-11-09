package ru.archlabs.chat.api;

import ru.archlabs.chat.domain.UserState;

public final class UserFacade {
    private final ChatGateway gateway;

    UserFacade(ChatGateway gateway) {
        this.gateway = gateway;
    }

    public UserState find(String user) {
        return gateway.user(FacadeSupport.requireUser(user));
    }
}
