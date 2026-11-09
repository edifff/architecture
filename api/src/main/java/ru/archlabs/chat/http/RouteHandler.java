package ru.archlabs.chat.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;

import java.util.regex.Matcher;

@FunctionalInterface
interface RouteHandler {
    void handle(ChannelHandlerContext ctx, FullHttpRequest request, Matcher matcher);
}
