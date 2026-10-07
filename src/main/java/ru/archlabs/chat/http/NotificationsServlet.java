package ru.archlabs.chat.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** {@code PUT /notifications/{username}} — включение/выключение уведомлений. */
final class NotificationsServlet extends ChatServlet {
    private static final long serialVersionUID = 1L;

    NotificationsServlet(ChatHttpServer server) {
        super(server);
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
        execute(response, () -> {
            var username = username(request);
            var connection = server.connection(username);
            if (connection == null) {
                throw new IllegalStateException("Session is not active: " + username);
            }
            var body = readBody(request, ChatProtocol.NotificationsRequest.class);
            connection.session().setNotificationsEnabled(body.enabled());
            response.setStatus(204);
        });
    }
}
