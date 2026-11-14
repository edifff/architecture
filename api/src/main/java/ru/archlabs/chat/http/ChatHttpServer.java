package ru.archlabs.chat.http;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpMethod;

import ru.archlabs.chat.api.ChatFacades;

/**
 * Netty HTTP entry point. Notifications use Server-Sent Events.
 */
public final class ChatHttpServer implements AutoCloseable {
    private static final String SESSIONS_PATH = "^/sessions/(?<user>[^/]+)$";
    private static final String MESSAGES_PATH = "^/messages/(?<user>[^/]+)$";

    private final int port;
    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private final ServerBootstrap bootstrap;
    private volatile Channel serverChannel;

    public ChatHttpServer(ChatFacades facades, int port) {
        Objects.requireNonNull(facades, "facades");
        this.port = port;
        ChatRequestRouter router =
                new ChatRequestRouter(
                        List.of(
                                new Route(
                                        HttpMethod.POST.name(),
                                        Pattern.compile(SESSIONS_PATH),
                                        new SessionsHandler(facades.sessions())),
                                new Route(
                                        HttpMethod.DELETE.name(),
                                        Pattern.compile(SESSIONS_PATH),
                                        new SessionsHandler(facades.sessions())),
                                new Route(
                                        HttpMethod.GET.name(),
                                        Pattern.compile("^/users/(?<user>[^/]+)$"),
                                        new UsersHandler(facades.users())),
                                new Route(
                                        HttpMethod.POST.name(),
                                        Pattern.compile(MESSAGES_PATH),
                                        new MessagesHandler(facades.messages())),
                                new Route(
                                        HttpMethod.GET.name(),
                                        Pattern.compile(MESSAGES_PATH),
                                        new MessagesHandler(facades.messages())),
                                new Route(
                                        HttpMethod.PUT.name(),
                                        Pattern.compile("^/notifications/(?<user>[^/]+)$"),
                                        new NotificationsHandler(facades.notifications())),
                                new Route(
                                        HttpMethod.GET.name(),
                                        Pattern.compile("^/events/(?<user>[^/]+)$"),
                                        new EventsHandler(
                                                facades.notifications(), facades.users()))));
        bootstrap =
                new ServerBootstrap()
                        .group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(new ChatChannelInitializer(router));
    }

    public void start() throws InterruptedException {
        serverChannel = bootstrap.bind(port).sync().channel();
    }

    public URI baseUri() {
        Channel channel = serverChannel;
        if (channel == null) {
            throw new IllegalStateException("HTTP server is not started");
        }
        InetSocketAddress address = (InetSocketAddress) channel.localAddress();
        return URI.create("http://localhost:" + address.getPort());
    }

    @Override
    public void close() {
        Channel channel = serverChannel;
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
        bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
    }
}
