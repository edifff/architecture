package ru.archlabs.chat.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import ru.archlabs.chat.api.ChatJson;
import ru.archlabs.chat.api.ChatProtocol;
import ru.archlabs.chat.api.MessagePayload;
import ru.archlabs.chat.api.NotificationFacade;
import ru.archlabs.proto.common.Message;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class EventsHandler implements RouteHandler {
    private static final long KEEP_ALIVE_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final long POLL_DELAY_MILLIS = 100L;

    private final NotificationFacade notifications;
    private final ChatHttpServer server;

    EventsHandler(NotificationFacade notifications, ChatHttpServer server) {
        this.notifications = notifications;
        this.server = server;
    }

    @Override
    public boolean supports(String method, String section) {
        return section.equals("events") && method.equals("GET");
    }

    @Override
    public void handle(ChannelHandlerContext ctx, Route route, FullHttpRequest request) {
        var username = route.username();
        var connection = server.connection(username);
        if (connection == null) {
            ResponseWriter.error(
                    ctx, HttpResponseStatus.NOT_FOUND, "Unknown user: " + username);
            return;
        }
        if (connection.subscription() == null) {
            connection.subscription(
                    notifications.subscribe(username, connection.pending()::offer));
        }
        ctx.channel().attr(ConnectionAttribute.KEY).set(connection);

        var response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, ChatProtocol.CONTENT_TYPE_EVENT_STREAM);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_CACHE);
        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        ctx.writeAndFlush(response);
        writeChunk(ctx, ": connected\n\n");
        connection.lastWriteNanos(System.nanoTime());

        connection.cancelSse();
        connection.sseTask(
                ctx.executor()
                        .scheduleWithFixedDelay(
                                () -> pollEvents(ctx, connection),
                                POLL_DELAY_MILLIS,
                                POLL_DELAY_MILLIS,
                                TimeUnit.MILLISECONDS));
    }

    private void pollEvents(ChannelHandlerContext ctx, ClientConnection connection) {
        try {
            var pending = connection.pending();
            Message message;
            boolean wroteAny = false;
            while ((message = pending.poll()) != null) {
                writeChunk(
                        ctx,
                        "event: "
                                + ChatProtocol.MESSAGE_EVENT
                                + "\n"
                                + ChatProtocol.DATA_PREFIX
                                + " "
                                + ChatJson.write(MessagePayload.from(message))
                                + "\n\n");
                wroteAny = true;
            }
            if (wroteAny) {
                connection.lastWriteNanos(System.nanoTime());
            } else if (System.nanoTime() - connection.lastWriteNanos() >= KEEP_ALIVE_NANOS) {
                writeChunk(ctx, ": keep-alive\n\n");
                connection.lastWriteNanos(System.nanoTime());
            }
        } catch (RuntimeException exception) {
            connection.cancelSse();
            ctx.close();
        }
    }

    private void writeChunk(ChannelHandlerContext ctx, String text) {
        ctx.writeAndFlush(
                new DefaultHttpContent(Unpooled.copiedBuffer(text, StandardCharsets.UTF_8)));
    }
}
