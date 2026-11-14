package ru.archlabs.chat.http;

import java.util.regex.Matcher;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;

import ru.archlabs.chat.api.UserFacade;

final class UsersHandler implements RouteHandler {
    private final UserFacade users;

    UsersHandler(UserFacade users) {
        this.users = users;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, FullHttpRequest request, Matcher matcher) {
        ResponseWriter.json(ctx, HttpResponseStatus.OK, users.find(matcher.group("user")));
    }
}
