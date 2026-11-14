package ru.archlabs.chat.http;

import java.util.regex.Matcher;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;

@FunctionalInterface
interface RouteHandler {
    void handle(ChannelHandlerContext ctx, FullHttpRequest request, Matcher matcher);
}
