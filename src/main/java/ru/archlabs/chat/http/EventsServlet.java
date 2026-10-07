package ru.archlabs.chat.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.TimeUnit;

/**
 * {@code GET /events/{username}} — поток оповещений в формате Server-Sent Events.
 * Соединение остаётся открытым, сервер отправляет события {@code message}.
 */
final class EventsServlet extends ChatServlet {
    private static final long serialVersionUID = 1L;
    private static final long KEEP_ALIVE_MILLIS = 15_000L;

    EventsServlet(ChatHttpServer server) {
        super(server);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String name;
        try {
            name = username(request);
        } catch (IllegalArgumentException exception) {
            error(response, 400, exception.getMessage());
            return;
        }

        var connection = server.connection(name);
        if (connection == null) {
            error(response, 404, "Unknown user: " + name);
            return;
        }

        response.setStatus(200);
        response.setContentType(ChatProtocol.CONTENT_TYPE_EVENT_STREAM);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        var writer = response.getWriter();
        writer.write(": connected\n\n");
        writer.flush();

        try {
            stream(connection, writer);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void stream(ClientConnection connection, PrintWriter writer)
            throws InterruptedException {
        while (!writer.checkError()) {
            var message = connection.pending().poll(KEEP_ALIVE_MILLIS, TimeUnit.MILLISECONDS);
            if (message == null) {
                writer.write(": keep-alive\n\n");
            } else {
                writer.write("event: " + ChatProtocol.MESSAGE_EVENT + "\n");
                writer.write(
                        ChatProtocol.DATA_PREFIX
                                + " "
                                + ChatJson.write(ChatProtocol.MessagePayload.from(message))
                                + "\n\n");
            }
            writer.flush();
        }
    }
}
