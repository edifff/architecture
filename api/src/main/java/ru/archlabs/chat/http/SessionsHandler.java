package ru.archlabs.chat.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import ru.archlabs.chat.api.SessionFacade;
import ru.archlabs.chat.api.SessionPayload;

final class SessionsHandler implements RouteHandler {
    private final SessionFacade sessions;
    private final ChatHttpServer server;

    SessionsHandler(SessionFacade sessions, ChatHttpServer server) {
        this.sessions = sessions;
        this.server = server;
    }

    @Override
    public boolean supports(String method, String section) {
        return section.equals("sessions") && (method.equals("POST") || method.equals("DELETE"));
    }

    @Override
    public void handle(ChannelHandlerContext ctx, Route route, FullHttpRequest request) {
        var username = route.username();
        if (request.method().name().equals("POST")) {
            sessions.connect(username);
            server.open(username);
            ResponseWriter.json(ctx, HttpResponseStatus.CREATED, new SessionPayload(username));
            return;
        }
        try {
            sessions.disconnect(username);
        } finally {
            server.closeConnection(username);
        }
        ResponseWriter.empty(ctx, HttpResponseStatus.NO_CONTENT);
    }
}
