package ru.archlabs.chat.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import ru.archlabs.chat.api.ChatJson;
import ru.archlabs.chat.api.ChatProtocol;
import ru.archlabs.chat.api.ErrorPayload;
import ru.archlabs.chat.api.FacadeRequest;
import ru.archlabs.chat.api.FacadeResponse;
import ru.archlabs.chat.api.MessagePayload;
import ru.archlabs.proto.common.Message;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class ChatServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final long KEEP_ALIVE_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final long POLL_DELAY_MILLIS = 100L;

    private final ChatHttpServer server;
    private ScheduledFuture<?> sseTask;
    private long lastWriteNanos;

    ChatServerHandler(ChatHttpServer server) {
        this.server = server;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            var facadeRequest =
                    new FacadeRequest(
                            request.method().name(),
                            java.net.URI.create(request.uri()).getPath(),
                            request.content().toString(StandardCharsets.UTF_8));
            var response = server.facade().handle(facadeRequest);
            switch (response.action()) {
                case OPEN -> {
                    server.open(response.username());
                    write(ctx, response);
                }
                case CLOSE -> {
                    server.closeConnection(response.username());
                    write(ctx, response);
                }
                case STREAM -> streamEvents(ctx, response.username());
                case NONE -> write(ctx, response);
            }
        } catch (IllegalStateException exception) {
            writeError(ctx, HttpResponseStatus.CONFLICT, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            writeError(ctx, HttpResponseStatus.BAD_REQUEST, exception.getMessage());
        } catch (RuntimeException exception) {
            writeError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
        }
    }

    private void streamEvents(ChannelHandlerContext ctx, String username) {
        var connection = server.connection(username);
        if (connection == null) {
            writeError(ctx, HttpResponseStatus.NOT_FOUND, "Unknown user: " + username);
            return;
        }
        if (connection.subscription() == null) {
            connection.subscription(
                    server.facade().subscribe(username, connection.pending()::offer));
        }

        var response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, ChatProtocol.CONTENT_TYPE_EVENT_STREAM);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_CACHE);
        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        ctx.writeAndFlush(response);
        writeChunk(ctx, ": connected\n\n");
        lastWriteNanos = System.nanoTime();

        cancelSse();
        sseTask =
                ctx.executor()
                        .scheduleWithFixedDelay(
                                () -> pollEvents(ctx, connection),
                                POLL_DELAY_MILLIS,
                                POLL_DELAY_MILLIS,
                                TimeUnit.MILLISECONDS);
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
                lastWriteNanos = System.nanoTime();
            } else if (System.nanoTime() - lastWriteNanos >= KEEP_ALIVE_NANOS) {
                writeChunk(ctx, ": keep-alive\n\n");
                lastWriteNanos = System.nanoTime();
            }
        } catch (RuntimeException exception) {
            cancelSse();
            ctx.close();
        }
    }

    private void writeChunk(ChannelHandlerContext ctx, String text) {
        ctx.writeAndFlush(
                new DefaultHttpContent(Unpooled.copiedBuffer(text, StandardCharsets.UTF_8)));
    }

    private void write(ChannelHandlerContext ctx, FacadeResponse response) {
        var status = HttpResponseStatus.valueOf(response.status());
        if (response.body() == null || response.body().isEmpty()) {
            var full = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
            full.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            ctx.writeAndFlush(full);
            return;
        }
        var bytes = Unpooled.copiedBuffer(response.body(), StandardCharsets.UTF_8);
        var full = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, bytes);
        full.headers().set(HttpHeaderNames.CONTENT_TYPE, response.contentType());
        full.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.readableBytes());
        ctx.writeAndFlush(full);
    }

    private void writeError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        if (!ctx.channel().isActive()) {
            return;
        }
        var body = ChatJson.write(new ErrorPayload(message));
        var bytes = Unpooled.copiedBuffer(body, StandardCharsets.UTF_8);
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, bytes);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, ChatProtocol.CONTENT_TYPE_JSON);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.readableBytes());
        ctx.writeAndFlush(response);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cancelSse();
        super.channelInactive(ctx);
    }

    private void cancelSse() {
        if (sseTask != null) {
            sseTask.cancel(false);
            sseTask = null;
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cancelSse();
        ctx.close();
    }
}
