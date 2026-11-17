package ru.archlabs.chat.http;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;

import ru.archlabs.chat.api.StatusFacade;

final class StatusHandler implements RouteHandler {
    private final StatusFacade status;

    StatusHandler(StatusFacade status) {
        this.status = status;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, FullHttpRequest request, Matcher matcher) {
        String body = request.content().toString(StandardCharsets.UTF_8);
        ResponseWriter.json(
                ctx,
                HttpResponseStatus.OK,
                status.broadcast(matcher.group("user"), body));
    }
}
