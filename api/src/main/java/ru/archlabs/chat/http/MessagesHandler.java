package ru.archlabs.chat.http;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;

import ru.archlabs.chat.api.MessageFacade;

final class MessagesHandler implements RouteHandler {
    private final MessageFacade messages;

    MessagesHandler(MessageFacade messages) {
        this.messages = messages;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, FullHttpRequest request, Matcher matcher) {
        String user = matcher.group("user");
        if (request.method().equals(HttpMethod.POST)) {
            String body = request.content().toString(StandardCharsets.UTF_8);
            ResponseWriter.json(
                    ctx, HttpResponseStatus.CREATED, messages.send(user, body));
        } else if (request.method().equals(HttpMethod.GET)) {
            QueryStringDecoder query = new QueryStringDecoder(request.uri());
            List<String> limits = query.parameters().get("limit");
            String limit = limits == null || limits.isEmpty() ? null : limits.getFirst();
            ResponseWriter.json(ctx, HttpResponseStatus.OK, messages.history(user, limit));
        } else {
            ResponseWriter.error(
                    ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Unsupported message method");
        }
    }
}
