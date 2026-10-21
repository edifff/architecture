package ru.archlabs.chat.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import ru.archlabs.chat.api.NotificationFacade;

import java.nio.charset.StandardCharsets;

final class NotificationsHandler implements RouteHandler {
    private final NotificationFacade notifications;

    NotificationsHandler(NotificationFacade notifications) {
        this.notifications = notifications;
    }

    @Override
    public boolean supports(String method, String section) {
        return section.equals("notifications") && method.equals("PUT");
    }

    @Override
    public void handle(ChannelHandlerContext ctx, Route route, FullHttpRequest request) {
        var body = request.content().toString(StandardCharsets.UTF_8);
        notifications.setNotificationsEnabled(route.username(), body);
        ResponseWriter.empty(ctx, HttpResponseStatus.NO_CONTENT);
    }
}
