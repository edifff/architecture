package ru.archlabs.chat.api;

import ru.archlabs.chat.domain.ChatMessage;

import java.util.List;

public final class MessageFacade {
    private final ChatGateway gateway;

    MessageFacade(ChatGateway gateway) {
        this.gateway = gateway;
    }

    public ChatMessage send(String user, String body) {
        var request = ChatJson.read(body, SendMessageRequest.class);
        return gateway.send(
                FacadeSupport.requireUser(user), FacadeSupport.requireText(request.text()));
    }

    public List<ChatMessage> history(String user, String limit) {
        return gateway.history(
                FacadeSupport.requireUser(user), FacadeSupport.parseLimit(limit));
    }
}
