package ru.archlabs.chat.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ru.archlabs.chat.Message;

import java.io.IOException;
import java.util.List;

/** {@code POST /messages/{username}} — отправка, {@code GET} — история. */
final class MessagesServlet extends ChatServlet {
    private static final long serialVersionUID = 1L;

    MessagesServlet(ChatHttpServer server) {
        super(server);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        execute(response, () -> {
            ClientConnection connection = connection(request);
            ChatProtocol.SendMessageRequest body = readBody(request, ChatProtocol.SendMessageRequest.class);
            Message message = connection.session().send(body.text());
            writeJson(response, 201, ChatProtocol.MessagePayload.from(message));
        });
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        execute(response, () -> {
            ClientConnection connection = connection(request);
            List<Message> history = connection.session().history();
            writeJson(response, 200, history.stream().map(ChatProtocol.MessagePayload::from).toList());
        });
    }

    private ClientConnection connection(HttpServletRequest request) {
        String username = username(request);
        ClientConnection connection = server.connection(username);
        if (connection == null) {
            throw new IllegalStateException("Session is not active: " + username);
        }
        return connection;
    }
}
