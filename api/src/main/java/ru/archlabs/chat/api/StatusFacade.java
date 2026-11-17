package ru.archlabs.chat.api;

/**
 * Facade for the general operation that applies to every chat.
 */
public final class StatusFacade {
    private final ChatGateway gateway;

    StatusFacade(ChatGateway gateway) {
        this.gateway = gateway;
    }

    public StatusReport broadcast(String user, String body) {
        StatusMessageRequest request = ChatJson.read(body, StatusMessageRequest.class);
        return gateway.broadcastStatus(
                FacadeSupport.requireUser(user), FacadeSupport.requireText(request.text()));
    }
}
