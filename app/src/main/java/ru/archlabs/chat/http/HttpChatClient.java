package ru.archlabs.chat.http;

import ru.archlabs.chat.ChatServer;
import ru.archlabs.chat.ChatSession;
import ru.archlabs.chat.Message;
import ru.archlabs.chat.UserState;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Клиент чата, работающий по HTTP. Реализует тот же интерфейс {@link ChatServer},
 * что и локальная реализация, поэтому вызывающий код не зависит от транспорта.
 */
public final class HttpChatClient implements ChatServer {
    private final URI baseUri;
    private final HttpClient client;
    private final Set<HttpChatSession> sessions = ConcurrentHashMap.newKeySet();

    public HttpChatClient(URI baseUri) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    @Override
    public ChatSession connect(String username, Consumer<Message> onMessage) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(onMessage, "onMessage");
        HttpResponse<String> response = send(builder("/sessions/" + encode(username))
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build());
        expect(response, 201, "connect");
        var session = new HttpChatSession(this, username, onMessage);
        sessions.add(session);
        return session;
    }

    @Override
    public UserState user(String username) {
        Objects.requireNonNull(username, "username");
        HttpResponse<String> response = get("/users/" + encode(username));
        if (response.statusCode() == 404) {
            throw new IllegalArgumentException("Unknown user: " + username);
        }
        expect(response, 200, "read user");
        return ChatJson.read(response.body(), ChatProtocol.UserPayload.class).toUserState();
    }

    @Override
    public void close() {
        for (HttpChatSession session : List.copyOf(sessions)) {
            session.close();
        }
        client.close();
    }

    URI resolve(String path) {
        return baseUri.resolve(path);
    }

    Set<HttpChatSession> sessions() {
        return sessions;
    }

    HttpResponse<Stream<String>> sendStream(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (IOException exception) {
            throw new IllegalStateException("HTTP stream failed: " + request.uri(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP stream interrupted: " + request.uri(), exception);
        }
    }

    HttpResponse<String> get(String path) {
        return send(builder(path).GET().build());
    }

    HttpResponse<String> post(String path, Object body) {
        return send(builder(path)
                        .header("Content-Type", ChatProtocol.CONTENT_TYPE_JSON)
                        .POST(HttpRequest.BodyPublishers.ofString(ChatJson.write(body), StandardCharsets.UTF_8))
                        .build());
    }

    HttpResponse<String> put(String path, Object body) {
        return send(builder(path)
                        .header("Content-Type", ChatProtocol.CONTENT_TYPE_JSON)
                        .PUT(HttpRequest.BodyPublishers.ofString(ChatJson.write(body), StandardCharsets.UTF_8))
                        .build());
    }

    HttpResponse<String> delete(String path) {
        return send(builder(path).DELETE().build());
    }

    void expect(HttpResponse<String> response, int status, String action) {
        if (response.statusCode() == status) {
            return;
        }
        String message = errorMessage(response);
        int code = response.statusCode();
        if (code == 409) {
            throw new IllegalStateException(message);
        }
        if (code == 400 || code == 404) {
            throw new IllegalArgumentException(message);
        }
        throw new IllegalStateException("Cannot " + action + ": HTTP " + code + " " + message);
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private HttpRequest.Builder builder(String path) {
        return HttpRequest.newBuilder(baseUri.resolve(path))
                .header("Accept", ChatProtocol.CONTENT_TYPE_JSON);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return client.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("HTTP request failed: " + request.uri(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP request interrupted: " + request.uri(), exception);
        }
    }

    private static String errorMessage(HttpResponse<String> response) {
        try {
            return ChatJson.read(response.body(), ChatProtocol.ErrorPayload.class).error();
        } catch (RuntimeException exception) {
            return response.body();
        }
    }
}
