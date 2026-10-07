package ru.archlabs.chat.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** {@code GET /users/{username}} — состояние пользователя. */
final class UserServlet extends ChatServlet {
    private static final long serialVersionUID = 1L;

    UserServlet(ChatHttpServer server) {
        super(server);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        execute(response, () -> {
            var username = username(request);
            ChatProtocol.UserPayload payload;
            try {
                payload = ChatProtocol.UserPayload.from(server.user(username));
            } catch (IllegalArgumentException exception) {
                error(response, 404, exception.getMessage());
                return;
            }
            writeJson(response, 200, payload);
        });
    }
}
