package ru.archlabs.chat.http;

import java.util.regex.Matcher;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

import ru.archlabs.chat.api.SessionFacade;

final class SessionsHandler implements RouteHandler {
    private final SessionFacade sessions;

    SessionsHandler(SessionFacade sessions) {
        this.sessions = sessions;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, FullHttpRequest request, Matcher matcher) {
        String user = matcher.group("user");
        if (request.method().equals(HttpMethod.POST)) {
            ResponseWriter.json(ctx, HttpResponseStatus.CREATED, sessions.connect(user));
        } else if (request.method().equals(HttpMethod.DELETE)) {
            sessions.disconnect(user);
            ResponseWriter.empty(ctx, HttpResponseStatus.NO_CONTENT);
        } else {
            ResponseWriter.error(
                    ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Unsupported session method");
        }
    }
}
