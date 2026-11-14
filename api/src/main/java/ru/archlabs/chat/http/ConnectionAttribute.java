package ru.archlabs.chat.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;

final class ConnectionAttribute {
    static final AttributeKey<AutoCloseable> EVENT_SUBSCRIPTION =
            AttributeKey.valueOf("chat.eventSubscription");

    private ConnectionAttribute() {
    }

    static void closeSubscription(ChannelHandlerContext ctx) {
        AutoCloseable subscription = ctx.channel().attr(EVENT_SUBSCRIPTION).getAndSet(null);
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception ignored) {
            }
        }
    }
}
