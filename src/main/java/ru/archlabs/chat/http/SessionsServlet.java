package ru.archlabs.chat.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** {@code POST /sessions/{username}} — подключение, {@code DELETE} — отключение. */
final class SessionsServlet extends ChatServlet {
    private static final long serialVersionUID = 1L;

    SessionsServlet(ChatHttpServer server) {
        super(server);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        execute(response, () -> {
            var username = username(request);
            server.connect(username);
            writeJson(response, 201, new ChatProtocol.SessionPayload(username));
        });
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        execute(response, () -> {
            var username = username(request);
            server.disconnect(username);
            response.setStatus(204);
        });
    }
}
