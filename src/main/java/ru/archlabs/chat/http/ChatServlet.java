package ru.archlabs.chat.http;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

abstract class ChatServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    protected final transient ChatHttpServer server;

    ChatServlet(ChatHttpServer server) {
        this.server = server;
    }

    @FunctionalInterface
    protected interface Action {
        void run() throws IOException;
    }

    protected static void execute(HttpServletResponse response, Action action) throws IOException {
        try {
            action.run();
        } catch (IllegalStateException exception) {
            error(response, 409, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            error(response, 400, exception.getMessage());
        } catch (RuntimeException exception) {
            error(response, 500, exception.getMessage());
        }
    }

    /** Возвращает имя пользователя из пути вида {@code /{username}}. */
    protected static String username(HttpServletRequest request) {
        String path = request.getPathInfo();
        if (path == null || path.length() <= 1) {
            throw new IllegalArgumentException("Username must not be blank");
        }
        String username = path.substring(1);
        if (username.isBlank()) {
            throw new IllegalArgumentException("Username must not be blank");
        }
        return username;
    }

    protected static <T> T readBody(HttpServletRequest request, Class<T> type) throws IOException {
        return ChatJson.read(request.getReader(), type);
    }

    protected static void writeJson(HttpServletResponse response, int status, Object value)
            throws IOException {
        response.setStatus(status);
        response.setContentType(ChatProtocol.CONTENT_TYPE_JSON);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(ChatJson.write(value));
    }

    protected static void error(HttpServletResponse response, int status, String message) {
        if (response.isCommitted()) {
            return;
        }
        try {
            writeJson(response, status, new ChatProtocol.ErrorPayload(message));
        } catch (IOException ignored) {
        }
    }
}
