package ru.archlabs.chat.http;

import java.util.regex.Matcher;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;

import ru.archlabs.chat.api.NotificationFacade;
import ru.archlabs.chat.api.UserFacade;
import ru.archlabs.chat.domain.ChatConflictException;

final class EventsHandler implements RouteHandler {
    private final NotificationFacade notifications;
    private final UserFacade users;

    EventsHandler(NotificationFacade notifications, UserFacade users) {
        this.notifications = notifications;
        this.users = users;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, FullHttpRequest request, Matcher matcher) {
        String user = matcher.group("user");
        if (!users.find(user).online()) {
            throw new ChatConflictException("User is not connected: " + user);
        }
        ResponseWriter.beginEvents(ctx);
        try {
            AutoCloseable subscription =
                    notifications.subscribe(
                            user, message -> ResponseWriter.event(ctx, "message", message));
            ctx.channel().attr(ConnectionAttribute.EVENT_SUBSCRIPTION).set(subscription);
        } catch (RuntimeException exception) {
            ctx.close();
        }
    }
}
