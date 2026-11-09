package ru.archlabs.chat.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import ru.archlabs.chat.api.NotificationFacade;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;

final class NotificationsHandler implements RouteHandler {
    private final NotificationFacade notifications;

    NotificationsHandler(NotificationFacade notifications) {
        this.notifications = notifications;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, FullHttpRequest request, Matcher matcher) {
        var body = request.content().toString(StandardCharsets.UTF_8);
        notifications.setEnabled(matcher.group("user"), body);
        ResponseWriter.empty(ctx, HttpResponseStatus.NO_CONTENT);
    }
}
