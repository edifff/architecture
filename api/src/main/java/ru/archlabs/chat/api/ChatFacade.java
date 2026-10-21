package ru.archlabs.chat.api;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import ru.archlabs.proto.chat.ChatServiceGrpc;
import ru.archlabs.proto.chat.ConnectRequest;
import ru.archlabs.proto.chat.DisconnectRequest;
import ru.archlabs.proto.chat.HistoryRequest;
import ru.archlabs.proto.chat.SendRequest;
import ru.archlabs.proto.chat.SetNotificationsEnabledRequest;
import ru.archlabs.proto.chat.SubscribeRequest;
import ru.archlabs.proto.chat.UserRequest;
import ru.archlabs.proto.common.Message;
import ru.archlabs.proto.common.UserState;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public final class ChatFacade {
    private static final Set<String> SECTIONS =
            Set.of("sessions", "users", "messages", "notifications", "events");

    private final ChatServiceGrpc.ChatServiceBlockingStub blocking;

    public ChatFacade(ManagedChannel channel) {
        this.blocking = ChatServiceGrpc.newBlockingStub(channel);
    }

    public FacadeResponse handle(FacadeRequest request) {
        try {
            var route = route(request.path());
            if (route == null) {
                return FacadeResponse.error(
                        404, "Unknown route: " + request.method() + " " + request.path());
            }
            return switch (request.method()) {
                case "GET" -> handleGet(route);
                case "POST" -> handlePost(route, request.body());
                case "PUT" -> handlePut(route, request.body());
                case "DELETE" -> handleDelete(route);
                default ->
                        FacadeResponse.error(
                                404,
                                "Unknown route: " + request.method() + " " + request.path());
            };
        } catch (StatusRuntimeException exception) {
            return FacadeResponse.error(
                    status(exception.getStatus().getCode()),
                    exception.getStatus().getDescription());
        } catch (IllegalArgumentException exception) {
            return FacadeResponse.error(400, exception.getMessage());
        } catch (RuntimeException exception) {
            return FacadeResponse.error(500, exception.getMessage());
        }
    }

    private FacadeResponse handleGet(Route route) {
        return switch (route.section()) {
            case "users" ->
                    FacadeResponse.json(200, UserPayload.from(user(route.username())));
            case "messages" ->
                    FacadeResponse.json(
                            200,
                            history(route.username()).stream()
                                    .map(MessagePayload::from)
                                    .toList());
            case "events" -> FacadeResponse.stream(route.username());
            default -> FacadeResponse.error(404, unknown(route));
        };
    }

    private FacadeResponse handlePost(Route route, String body) {
        if (route.section().equals("sessions")) {
            connect(route.username());
            return FacadeResponse.opened(route.username());
        }
        if (route.section().equals("messages")) {
            var message = ChatJson.read(body, SendMessageRequest.class);
            return FacadeResponse.json(
                    201, MessagePayload.from(send(route.username(), message.text())));
        }
        return FacadeResponse.error(404, unknown(route));
    }

    private FacadeResponse handlePut(Route route, String body) {
        if (!route.section().equals("notifications")) {
            return FacadeResponse.error(404, unknown(route));
        }
        var message = ChatJson.read(body, NotificationsRequest.class);
        setNotificationsEnabled(route.username(), message.enabled());
        return FacadeResponse.empty(204);
    }

    private FacadeResponse handleDelete(Route route) {
        if (!route.section().equals("sessions")) {
            return FacadeResponse.error(404, unknown(route));
        }
        disconnect(route.username());
        return FacadeResponse.closed(route.username());
    }

    public UserState connect(String username) {
        requireUsername(username);
        return blocking.connect(ConnectRequest.newBuilder().setName(username).build()).getUser();
    }

    public void disconnect(String username) {
        requireUsername(username);
        blocking.disconnect(DisconnectRequest.newBuilder().setName(username).build());
    }

    public Message send(String username, String text) {
        requireUsername(username);
        requireText(text);
        return blocking.send(
                SendRequest.newBuilder().setName(username).setText(text).build());
    }

    public List<Message> history(String username) {
        requireUsername(username);
        return blocking.history(HistoryRequest.newBuilder().setName(username).build())
                .getMessagesList();
    }

    public void setNotificationsEnabled(String username, boolean enabled) {
        requireUsername(username);
        blocking.setNotificationsEnabled(
                SetNotificationsEnabledRequest.newBuilder()
                        .setName(username)
                        .setEnabled(enabled)
                        .build());
    }

    public UserState user(String username) {
        requireUsername(username);
        return blocking.user(UserRequest.newBuilder().setName(username).build());
    }

    public Subscription subscribe(String username, Consumer<Message> onMessage) {
        requireUsername(username);
        var thread =
                new Thread(
                        () -> {
                            var stream =
                                    blocking.subscribe(
                                            SubscribeRequest.newBuilder().setName(username).build());
                            try {
                                while (stream.hasNext()) {
                                    onMessage.accept(stream.next());
                                }
                            } catch (RuntimeException ignored) {
                            }
                        },
                        "facade-subscribe-" + username);
        thread.setDaemon(true);
        thread.start();
        return new Subscription(thread);
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

    private static String unknown(Route route) {
        return "Unknown route: /" + route.section() + "/" + route.username();
    }

    private static int status(Status.Code code) {
        return switch (code) {
            case ALREADY_EXISTS, FAILED_PRECONDITION -> 409;
            case INVALID_ARGUMENT -> 400;
            case NOT_FOUND -> 404;
            case UNAVAILABLE -> 503;
            default -> 500;
        };
    }

    private static void requireUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username must not be blank");
        }
    }

    private static void requireText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Message must not be blank");
        }
    }
}
