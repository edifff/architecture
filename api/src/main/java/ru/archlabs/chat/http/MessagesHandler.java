package ru.archlabs.chat.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import ru.archlabs.chat.api.MessageFacade;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;

final class MessagesHandler implements RouteHandler {
    private final MessageFacade messages;

    MessagesHandler(MessageFacade messages) {
        this.messages = messages;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, FullHttpRequest request, Matcher matcher) {
        var user = matcher.group("user");
        if (request.method().equals(HttpMethod.POST)) {
            var body = request.content().toString(StandardCharsets.UTF_8);
            ResponseWriter.json(
                    ctx, HttpResponseStatus.CREATED, messages.send(user, body));
        } else if (request.method().equals(HttpMethod.GET)) {
            var query = new QueryStringDecoder(request.uri());
            var limits = query.parameters().get("limit");
            var limit = limits == null || limits.isEmpty() ? null : limits.getFirst();
            ResponseWriter.json(ctx, HttpResponseStatus.OK, messages.history(user, limit));
        } else {
            ResponseWriter.error(
                    ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Unsupported message method");
        }
    }
}
