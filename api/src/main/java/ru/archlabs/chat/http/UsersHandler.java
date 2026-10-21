package ru.archlabs.chat.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import ru.archlabs.chat.api.UserFacade;
import ru.archlabs.chat.api.UserPayload;

final class UsersHandler implements RouteHandler {
    private final UserFacade users;

    UsersHandler(UserFacade users) {
        this.users = users;
    }

    @Override
    public boolean supports(String method, String section) {
        return section.equals("users") && method.equals("GET");
    }

    @Override
    public void handle(ChannelHandlerContext ctx, Route route, FullHttpRequest request) {
        ResponseWriter.json(
                ctx, HttpResponseStatus.OK, UserPayload.from(users.user(route.username())));
    }
}
