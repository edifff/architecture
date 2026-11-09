package ru.archlabs.chat.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import ru.archlabs.chat.domain.ChatConflictException;
import ru.archlabs.chat.domain.ChatException;
import ru.archlabs.chat.domain.ChatNotFoundException;
import ru.archlabs.chat.domain.ChatValidationException;

import java.util.List;

final class ChatRequestRouter {
    private final List<Route> routes;

    ChatRequestRouter(List<Route> routes) {
        this.routes = List.copyOf(routes);
    }

    void handle(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!request.decoderResult().isSuccess()) {
            ResponseWriter.error(ctx, HttpResponseStatus.BAD_REQUEST, "Malformed HTTP request");
            return;
        }
        var path = new QueryStringDecoder(request.uri()).path();
        for (var route : routes) {
            var matcher = route.pattern().matcher(path);
            if (route.method().equals(request.method().name()) && matcher.matches()) {
                try {
                    route.handler().handle(ctx, request, matcher);
                } catch (ChatValidationException exception) {
                    ResponseWriter.error(ctx, HttpResponseStatus.BAD_REQUEST, exception.getMessage());
                } catch (ChatConflictException exception) {
                    ResponseWriter.error(ctx, HttpResponseStatus.CONFLICT, exception.getMessage());
                } catch (ChatNotFoundException exception) {
                    ResponseWriter.error(ctx, HttpResponseStatus.NOT_FOUND, exception.getMessage());
                } catch (ChatException exception) {
                    ResponseWriter.error(
                            ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
                } catch (RuntimeException exception) {
                    ResponseWriter.error(
                            ctx,
                            HttpResponseStatus.INTERNAL_SERVER_ERROR,
                            "Internal server error");
                }
                return;
            }
        }
        ResponseWriter.error(ctx, HttpResponseStatus.NOT_FOUND, "Unknown route: " + path);
    }
}
