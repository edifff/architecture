package ru.archlabs.chat.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;

interface RouteHandler {
    boolean supports(String method, String section);

    void handle(ChannelHandlerContext ctx, Route route, FullHttpRequest request);
}
