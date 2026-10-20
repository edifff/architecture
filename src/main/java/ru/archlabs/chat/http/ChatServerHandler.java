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
import ru.archlabs.chat.Message;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Маршрутизатор HTTP-запросов на Netty. Заменяет прежние сервлеты: разбирает метод и путь,
 * выполняет операцию чата и пишет JSON-ответ либо поток Server-Sent Events. Один экземпляр
 * на канал; состояние — только запланированная задача SSE-выдачи.
 */
final class ChatServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final String CONTENT_TYPE_JSON = ChatProtocol.CONTENT_TYPE_JSON;
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
        var path = java.net.URI.create(request.uri()).getPath();
        var method = request.method().name();

        try {
            switch (method) {
                case "GET" -> handleGet(ctx, path, request);
                case "POST" -> handlePost(ctx, path, request);
                case "PUT" -> handlePut(ctx, path, request);
                case "DELETE" -> handleDelete(ctx, path);
                default -> writeError(ctx, HttpResponseStatus.NOT_FOUND, "Unknown route: " + method + " " + path);
            }
        } catch (IllegalStateException exception) {
            writeError(ctx, HttpResponseStatus.CONFLICT, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            writeError(ctx, HttpResponseStatus.BAD_REQUEST, exception.getMessage());
        } catch (RuntimeException exception) {
            writeError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
        }
    }

    // --- GET -----------------------------------------------------------------

    private void handleGet(ChannelHandlerContext ctx, String path, FullHttpRequest request) {
        if (path.equals("/metrics")) {
            writeText(
                    ctx,
                    HttpResponseStatus.OK,
                    "text/plain; version=0.0.4; charset=utf-8",
                    server.metrics().scrape());
            return;
        }
        var route = route(path);
        if (route == null) {
            writeError(ctx, HttpResponseStatus.NOT_FOUND, "Unknown route: GET " + path);
            return;
        }
        var username = route.username();
        switch (route.section()) {
            case "users" -> {
                ChatProtocol.UserPayload payload;
                try {
                    payload = ChatProtocol.UserPayload.from(server.user(username));
                } catch (IllegalArgumentException exception) {
                    // Неизвестный пользователь — 404, а не 400 (как в прежнем UserServlet).
                    writeError(ctx, HttpResponseStatus.NOT_FOUND, exception.getMessage());
                    return;
                }
                writeJson(ctx, HttpResponseStatus.OK, payload);
            }
            case "messages" -> {
                var connection = requireConnection(ctx, username);
                if (connection == null) {
                    return;
                }
                List<Message> history = connection.session().history();
                writeJson(
                        ctx,
                        HttpResponseStatus.OK,
                        history.stream().map(ChatProtocol.MessagePayload::from).toList());
            }
            case "events" -> streamEvents(ctx, username);
            default -> writeError(ctx, HttpResponseStatus.NOT_FOUND, "Unknown route: GET " + path);
        }
    }

    // --- POST ----------------------------------------------------------------

    private void handlePost(ChannelHandlerContext ctx, String path, FullHttpRequest request) {
        var route = route(path);
        if (route == null) {
            writeError(ctx, HttpResponseStatus.NOT_FOUND, "Unknown route: POST " + path);
            return;
        }
        var username = route.username();
        switch (route.section()) {
            case "sessions" -> {
                server.connect(username);
                writeJson(ctx, HttpResponseStatus.CREATED, new ChatProtocol.SessionPayload(username));
            }
            case "messages" -> {
                var connection = requireConnection(ctx, username);
                if (connection == null) {
                    return;
                }
                var body = ChatJson.read(content(request), ChatProtocol.SendMessageRequest.class);
                Message message = connection.session().send(body.text());
                writeJson(ctx, HttpResponseStatus.CREATED, ChatProtocol.MessagePayload.from(message));
            }
            default -> writeError(ctx, HttpResponseStatus.NOT_FOUND, "Unknown route: POST " + path);
        }
    }

    // --- PUT -----------------------------------------------------------------

    private void handlePut(ChannelHandlerContext ctx, String path, FullHttpRequest request) {
        var route = route(path);
        if (route == null || !route.section().equals("notifications")) {
            writeError(ctx, HttpResponseStatus.NOT_FOUND, "Unknown route: PUT " + path);
            return;
        }
        var username = route.username();
        var connection = requireConnection(ctx, username);
        if (connection == null) {
            return;
        }
        var body = ChatJson.read(content(request), ChatProtocol.NotificationsRequest.class);
        connection.session().setNotificationsEnabled(body.enabled());
        writeEmpty(ctx, HttpResponseStatus.NO_CONTENT);
    }

    // --- DELETE --------------------------------------------------------------

    private void handleDelete(ChannelHandlerContext ctx, String path) {
        var route = route(path);
        if (route == null || !route.section().equals("sessions")) {
            writeError(ctx, HttpResponseStatus.NOT_FOUND, "Unknown route: DELETE " + path);
            return;
        }
        server.disconnect(route.username());
        writeEmpty(ctx, HttpResponseStatus.NO_CONTENT);
    }

    // --- SSE -----------------------------------------------------------------

    private void streamEvents(ChannelHandlerContext ctx, String username) {
        var connection = server.connection(username);
        if (connection == null) {
            writeError(ctx, HttpResponseStatus.NOT_FOUND, "Unknown user: " + username);
            return;
        }

        var response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, ChatProtocol.CONTENT_TYPE_EVENT_STREAM);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_CACHE);
        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        ctx.writeAndFlush(response);
        writeChunk(ctx, ": connected\n\n");
        lastWriteNanos = System.nanoTime();

        // Отменяем предыдущую задачу выдачи (если поток уже был открыт на этом соединении),
        // иначе она навсегда утекла бы в event loop и полла бы мёртвый канал.
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
                                + ChatJson.write(ChatProtocol.MessagePayload.from(message))
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
            // Клиент отключился или канал закрыт — останавливаем выдачу.
            cancelSse();
            ctx.close();
        }
    }

    private void writeChunk(ChannelHandlerContext ctx, String text) {
        ctx.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer(text, StandardCharsets.UTF_8)));
    }

    // --- helpers -------------------------------------------------------------

    /** Возвращает соединение пользователя, либо пишет 409 и {@code null}, если сессии нет. */
    private ClientConnection requireConnection(ChannelHandlerContext ctx, String username) {
        var connection = server.connection(username);
        if (connection == null) {
            writeError(ctx, HttpResponseStatus.CONFLICT, "Session is not active: " + username);
            return null;
        }
        return connection;
    }

    private static String content(FullHttpRequest request) {
        return request.content().toString(StandardCharsets.UTF_8);
    }

    private static final Set<String> SECTIONS =
            Set.of("sessions", "users", "messages", "notifications", "events");

    private static Route route(String path) {
        if (path == null || !path.startsWith("/") || path.length() <= 1) {
            return null;
        }
        var parts = path.substring(1).split("/", -1);
        if (parts.length == 1) {
            // /users — известная секция без пользователя: это ошибка входных данных, а не
            // неизвестный маршрут (как в прежних сервлетах, где pathInfo был пуст).
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

    private record Route(String section, String username) {
    }

    private void writeJson(ChannelHandlerContext ctx, HttpResponseStatus status, Object payload) {
        writeText(ctx, status, CONTENT_TYPE_JSON, ChatJson.write(payload));
    }

    private void writeError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        if (!ctx.channel().isActive()) {
            return;
        }
        writeText(ctx, status, CONTENT_TYPE_JSON, ChatJson.write(new ChatProtocol.ErrorPayload(message)));
    }

    private void writeText(ChannelHandlerContext ctx, HttpResponseStatus status, String contentType, String body) {
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
}
