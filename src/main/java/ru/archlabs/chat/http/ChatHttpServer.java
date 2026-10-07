package ru.archlabs.chat.http;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import ru.archlabs.chat.ChatMetrics;
import ru.archlabs.chat.ChatServer;
import ru.archlabs.chat.UserState;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Встроенный HTTP-сервер чата на Jetty. Принимает сообщения сервлетами
 * (Java Servlet API) и перенаправляет их в {@link ChatServer}.
 */
public final class ChatHttpServer implements AutoCloseable {
    private final ChatServer chat;
    private final ChatMetrics metrics;
    private final Server server;
    private final Map<String, ClientConnection> connections = new ConcurrentHashMap<>();

    public ChatHttpServer(ChatServer chat, int port) {
        this.chat = Objects.requireNonNull(chat, "chat");
        this.metrics =
                chat instanceof ChatMetrics.Source source ? source.metrics() : new ChatMetrics();
        this.server = new Server(port);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        context.addServlet(new ServletHolder(new SessionsServlet(this)), "/sessions/*");
        context.addServlet(new ServletHolder(new UserServlet(this)), "/users/*");
        context.addServlet(new ServletHolder(new MessagesServlet(this)), "/messages/*");
        context.addServlet(new ServletHolder(new NotificationsServlet(this)), "/notifications/*");
        context.addServlet(new ServletHolder(new EventsServlet(this)), "/events/*");
        context.addServlet(new ServletHolder(new MetricsServlet(metrics)), "/metrics");
        server.setHandler(context);
    }

    public void start() throws Exception {
        server.start();
    }

    public URI baseUri() {
        ServerConnector connector = (ServerConnector) server.getConnectors()[0];
        return URI.create("http://localhost:" + connector.getLocalPort());
    }

    public ChatServer chat() {
        return chat;
    }

    synchronized ClientConnection connect(String username) {
        if (connections.containsKey(username)) {
            throw new IllegalStateException("User is already connected: " + username);
        }
        ClientConnection connection = new ClientConnection(username);
        connection.session(chat.connect(username, connection.pending()::offer));
        connections.put(username, connection);
        return connection;
    }

    synchronized void disconnect(String username) {
        ClientConnection connection = connections.remove(username);
        if (connection == null) {
            throw new IllegalArgumentException("Unknown user: " + username);
        }
        connection.session().close();
    }

    ClientConnection connection(String username) {
        return connections.get(username);
    }

    UserState user(String username) {
        return chat.user(username);
    }

    @Override
    public void close() {
        try {
            server.stop();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot stop HTTP chat server", exception);
        }
        chat.close();
    }
}
