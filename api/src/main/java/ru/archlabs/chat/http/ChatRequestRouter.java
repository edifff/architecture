package ru.archlabs.chat.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.util.List;

final class ChatRequestRouter {
    private final List<RouteHandler> handlers;

    ChatRequestRouter(List<RouteHandler> handlers) {
        this.handlers = handlers;
    }

    void dispatch(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            var path = java.net.URI.create(request.uri()).getPath();
            var method = request.method().name();
            var route = Route.parse(path);
            if (route == null) {
                ResponseWriter.error(
                        ctx, HttpResponseStatus.NOT_FOUND, "Unknown route: " + method + " " + path);
                return;
            }
            var handler =
                    handlers.stream()
                            .filter(candidate -> candidate.supports(method, route.section()))
                            .findFirst()
                            .orElse(null);
            if (handler == null) {
                ResponseWriter.error(
                        ctx, HttpResponseStatus.NOT_FOUND, "Unknown route: " + method + " " + path);
                return;
            }
            handler.handle(ctx, route, request);
        } catch (StatusRuntimeException exception) {
            ResponseWriter.error(
                    ctx,
                    status(exception.getStatus().getCode()),
                    exception.getStatus().getDescription());
        } catch (IllegalStateException exception) {
            ResponseWriter.error(ctx, HttpResponseStatus.CONFLICT, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            var message = exception.getMessage();
            var status =
                    message != null && message.startsWith("Unknown user")
                            ? HttpResponseStatus.NOT_FOUND
                            : HttpResponseStatus.BAD_REQUEST;
            ResponseWriter.error(ctx, status, message);
        } catch (RuntimeException exception) {
            ResponseWriter.error(
                    ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
        }
    }

    private static HttpResponseStatus status(Status.Code code) {
        return switch (code) {
            case ALREADY_EXISTS, FAILED_PRECONDITION -> HttpResponseStatus.CONFLICT;
            case INVALID_ARGUMENT -> HttpResponseStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpResponseStatus.NOT_FOUND;
            case UNAVAILABLE -> HttpResponseStatus.SERVICE_UNAVAILABLE;
            default -> HttpResponseStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
