package ru.archlabs.chat.api;

import java.util.List;

import ru.archlabs.chat.domain.Message;

public final class MessageFacade {
    private final ChatGateway gateway;

    MessageFacade(ChatGateway gateway) {
        this.gateway = gateway;
    }

    public Message send(String user, String body) {
        SendMessageRequest request = ChatJson.read(body, SendMessageRequest.class);
        return gateway.send(
                FacadeSupport.requireUser(user), FacadeSupport.requireText(request.text()));
    }

    public List<Message> history(String user, String limit) {
        return gateway.history(
                FacadeSupport.requireUser(user), FacadeSupport.parseLimit(limit));
    }
}
