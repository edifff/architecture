package ru.archlabs.chat.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import ru.archlabs.chat.api.ChatJson;
import ru.archlabs.chat.api.ChatProtocol;
import ru.archlabs.chat.api.ErrorPayload;

import java.nio.charset.StandardCharsets;

final class ResponseWriter {
    private ResponseWriter() {
    }

    static void json(ChannelHandlerContext ctx, HttpResponseStatus status, Object payload) {
        text(ctx, status, ChatProtocol.CONTENT_TYPE_JSON, ChatJson.write(payload));
    }

    static void error(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        if (!ctx.channel().isActive()) {
            return;
        }
        text(
                ctx,
                status,
                ChatProtocol.CONTENT_TYPE_JSON,
                ChatJson.write(new ErrorPayload(message)));
    }

    static void empty(ChannelHandlerContext ctx, HttpResponseStatus status) {
        var response =
                new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        ctx.writeAndFlush(response);
    }

    static void text(
            ChannelHandlerContext ctx, HttpResponseStatus status, String contentType, String body) {
        var bytes = Unpooled.copiedBuffer(body, StandardCharsets.UTF_8);
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, bytes);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.readableBytes());
        ctx.writeAndFlush(response);
    }
}
