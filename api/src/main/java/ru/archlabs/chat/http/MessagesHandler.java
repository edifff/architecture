package ru.archlabs.chat.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import ru.archlabs.chat.api.MessageFacade;
import ru.archlabs.chat.api.MessagePayload;

import java.nio.charset.StandardCharsets;

final class MessagesHandler implements RouteHandler {
    private final MessageFacade messages;

    MessagesHandler(MessageFacade messages) {
        this.messages = messages;
    }

    @Override
    public boolean supports(String method, String section) {
        return section.equals("messages") && (method.equals("POST") || method.equals("GET"));
    }

    @Override
    public void handle(ChannelHandlerContext ctx, Route route, FullHttpRequest request) {
        var username = route.username();
        if (request.method().name().equals("POST")) {
            var body = request.content().toString(StandardCharsets.UTF_8);
            ResponseWriter.json(
                    ctx,
                    HttpResponseStatus.CREATED,
                    MessagePayload.from(messages.send(username, body)));
            return;
        }
        ResponseWriter.json(
                ctx,
                HttpResponseStatus.OK,
                messages.history(username).stream().map(MessagePayload::from).toList());
    }
}
