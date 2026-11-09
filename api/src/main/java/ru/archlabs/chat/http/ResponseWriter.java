package ru.archlabs.chat.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import ru.archlabs.chat.api.ChatJson;
import ru.archlabs.chat.api.ErrorPayload;

import java.nio.charset.StandardCharsets;

final class ResponseWriter {
    private ResponseWriter() {
    }

    static void json(ChannelHandlerContext ctx, HttpResponseStatus status, Object body) {
        write(ctx, status, ChatProtocol.JSON, ChatJson.write(body));
    }

    static void error(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        json(ctx, status, new ErrorPayload(message));
    }

    static void empty(ChannelHandlerContext ctx, HttpResponseStatus status) {
        write(ctx, status, ChatProtocol.JSON, "");
    }

    static void beginEvents(ChannelHandlerContext ctx) {
        var response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, ChatProtocol.EVENT_STREAM);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_CACHE);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        HttpUtil.setTransferEncodingChunked(response, true);
        ctx.writeAndFlush(response);
    }

    static void event(ChannelHandlerContext ctx, String event, Object data) {
        if (!ctx.channel().isActive()) {
            return;
        }
        var payload = "event: " + event + "\n" + "data: " + ChatJson.write(data) + "\n\n";
        ctx.writeAndFlush(
                new DefaultHttpContent(Unpooled.copiedBuffer(payload, StandardCharsets.UTF_8)));
    }

    private static void write(
            ChannelHandlerContext ctx, HttpResponseStatus status, String contentType, String body) {
        if (!ctx.channel().isActive()) {
            return;
        }
        var content = Unpooled.copiedBuffer(body, StandardCharsets.UTF_8);
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        ctx.writeAndFlush(response);
    }
}
