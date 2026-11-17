package ru.archlabs.chat.http;

import java.util.regex.Matcher;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;

import ru.archlabs.chat.api.ShardFacade;

final class ShardHandler implements RouteHandler {
    private final ShardFacade shards;

    ShardHandler(ShardFacade shards) {
        this.shards = shards;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, FullHttpRequest request, Matcher matcher) {
        ResponseWriter.json(
                ctx, HttpResponseStatus.OK, shards.find(matcher.group("user")));
    }
}
