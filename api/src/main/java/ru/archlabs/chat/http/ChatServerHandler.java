package ru.archlabs.chat.http;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
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
import ru.archlabs.chat.api.MessagePayload;
import ru.archlabs.chat.api.SessionPayload;
import ru.archlabs.chat.api.UserPayload;
import ru.archlabs.proto.common.Message;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Транспорт: маршрутизация HTTP, запись ответов и SSE. Пользовательское сообщение
 * разбирается, проверяется и конвертируется в Facade.
 */
final class ChatServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final long KEEP_ALIVE_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final long POLL_DELAY_MILLIS = 100L;
    private static final Set<String> SECTIONS =
            Set.of("sessions", "users", "messages", "notifications", "events");

    private final ChatHttpServer server;
    private ScheduledFuture<?> sseTask;
    private long lastWriteNanos;

    ChatServerHandler(ChatHttpServer server) {
        this.server = server;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            var path = java.net.URI.create(request.uri()).getPath();
            var method = request.method().name();
            switch (method) {
                case "GET" -> handleGet(ctx, path);
                case "POST" -> handlePost(ctx, path, request);
                case "PUT" -> handlePut(ctx, path, request);
                case "DELETE" -> handleDelete(ctx, path);
                default ->
                        writeError(
                                ctx,
                                HttpResponseStatus.NOT_FOUND,
                                "Unknown route: " + method + " " + path);
            }
        } catch (StatusRuntimeException exception) {
            writeError(
                    ctx,
                    status(exception.getStatus().getCode()),
                    exception.getStatus().getDescription());
        } catch (IllegalStateException exception) {
            writeError(ctx, HttpResponseStatus.CONFLICT, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            var message = exception.getMessage();
            var status =
                    message != null && message.startsWith("Unknown user")
                            ? HttpResponseStatus.NOT_FOUND
                            : HttpResponseStatus.BAD_REQUEST;
            writeError(ctx, status, message);
        } catch (RuntimeException exception) {
            writeError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
        }
    }

    private void handleGet(ChannelHandlerContext ctx, String path) {
        var route = route(path);
        if (route == null) {
            writeError(ctx, HttpResponseStatus.NOT_FOUND, "Unknown route: GET " + path);
            return;
        }
        var username = route.username();
        switch (route.section()) {
            case "users" ->
                    writeJson(
                            ctx,
                            HttpResponseStatus.OK,
                            UserPayload.from(server.facade().user(username)));
            case "messages" ->
                    writeJson(
                            ctx,
                            HttpResponseStatus.OK,
                            server.facade().history(username).stream()
                                    .map(MessagePayload::from)
                                    .toList());
            case "events" -> streamEvents(ctx, username);
            default ->
                    writeError(ctx, HttpResponseStatus.NOT_FOUND, "Unknown route: GET " + path);
        }
    }

    private void handlePost(ChannelHandlerContext ctx, String path, FullHttpRequest request) {
        var route = route(path);
        if (route == null) {
            writeError(ctx, HttpResponseStatus.NOT_FOUND, "Unknown route: POST " + path);
            return;
        }
        var username = route.username();
        switch (route.section()) {
            case "sessions" -> {
                server.facade().connect(username);
                server.open(username);
                writeJson(ctx, HttpResponseStatus.CREATED, new SessionPayload(username));
            }
            case "messages" -> {
                var message = server.facade().send(username, content(request));
                writeJson(ctx, HttpResponseStatus.CREATED, MessagePayload.from(message));
            }
            default ->
                    writeError(ctx, HttpResponseStatus.NOT_FOUND, "Unknown route: POST " + path);
        }
    }

    private void handlePut(ChannelHandlerContext ctx, String path, FullHttpRequest request) {
        var route = route(path);
        if (route == null || !route.section().equals("notifications")) {
            writeError(ctx, HttpResponseStatus.NOT_FOUND, "Unknown route: PUT " + path);
            return;
        }
        server.facade().setNotificationsEnabled(route.username(), content(request));
        writeEmpty(ctx, HttpResponseStatus.NO_CONTENT);
    }

    private void handleDelete(ChannelHandlerContext ctx, String path) {
        var route = route(path);
        if (route == null || !route.section().equals("sessions")) {
            writeError(ctx, HttpResponseStatus.NOT_FOUND, "Unknown route: DELETE " + path);
            return;
        }
        try {
            server.facade().disconnect(route.username());
        } finally {
            server.closeConnection(route.username());
        }
        writeEmpty(ctx, HttpResponseStatus.NO_CONTENT);
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

    private static String content(FullHttpRequest request) {
        return request.content().toString(StandardCharsets.UTF_8);
    }

    private static Route route(String path) {
        if (path == null || !path.startsWith("/") || path.length() <= 1) {
            return null;
        }
        var parts = path.substring(1).split("/", -1);
        if (parts.length == 1) {
            if (SECTIONS.contains(parts[0])) {
                throw new IllegalArgumentException("Username must not be blank");
            }
            return null;
        }
        if (parts.length != 2) {
            return null;
        }
        var section = parts[0];
        var username = parts[1];
        if (!SECTIONS.contains(section)) {
            return null;
        }
        if (username.isBlank()) {
            throw new IllegalArgumentException("Username must not be blank");
        }
        return new Route(section, username);
    }

    private void writeJson(ChannelHandlerContext ctx, HttpResponseStatus status, Object payload) {
        writeText(ctx, status, ChatProtocol.CONTENT_TYPE_JSON, ChatJson.write(payload));
    }

    private void writeError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        if (!ctx.channel().isActive()) {
            return;
        }
        writeText(
                ctx,
                status,
                ChatProtocol.CONTENT_TYPE_JSON,
                ChatJson.write(new ErrorPayload(message)));
    }

    private void writeText(
            ChannelHandlerContext ctx, HttpResponseStatus status, String contentType, String body) {
        var bytes = Unpooled.copiedBuffer(body, StandardCharsets.UTF_8);
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, bytes);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.readableBytes());
        ctx.writeAndFlush(response);
    }

    private void writeEmpty(ChannelHandlerContext ctx, HttpResponseStatus status) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
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

    private static HttpResponseStatus status(Status.Code code) {
        return switch (code) {
            case ALREADY_EXISTS, FAILED_PRECONDITION -> HttpResponseStatus.CONFLICT;
            case INVALID_ARGUMENT -> HttpResponseStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpResponseStatus.NOT_FOUND;
            case UNAVAILABLE -> HttpResponseStatus.SERVICE_UNAVAILABLE;
            default -> HttpResponseStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
