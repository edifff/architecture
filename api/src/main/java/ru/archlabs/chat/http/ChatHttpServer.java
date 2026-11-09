package ru.archlabs.chat.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import ru.archlabs.chat.api.ChatFacades;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Netty HTTP entry point. Notifications use Server-Sent Events.
 */
public final class ChatHttpServer implements AutoCloseable {
    private final int port;
    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private final ServerBootstrap bootstrap;
    private volatile Channel serverChannel;

    public ChatHttpServer(ChatFacades facades, int port) {
        Objects.requireNonNull(facades, "facades");
        this.port = port;
        var router =
                new ChatRequestRouter(
                        List.of(
                                new Route(
                                        "POST",
                                        Pattern.compile("^/sessions/(?<user>[^/]+)$"),
                                        new SessionsHandler(facades.sessions())),
                                new Route(
                                        "DELETE",
                                        Pattern.compile("^/sessions/(?<user>[^/]+)$"),
                                        new SessionsHandler(facades.sessions())),
                                new Route(
                                        "GET",
                                        Pattern.compile("^/users/(?<user>[^/]+)$"),
                                        new UsersHandler(facades.users())),
                                new Route(
                                        "POST",
                                        Pattern.compile("^/messages/(?<user>[^/]+)$"),
                                        new MessagesHandler(facades.messages())),
                                new Route(
                                        "GET",
                                        Pattern.compile("^/messages/(?<user>[^/]+)$"),
                                        new MessagesHandler(facades.messages())),
                                new Route(
                                        "PUT",
                                        Pattern.compile("^/notifications/(?<user>[^/]+)$"),
                                        new NotificationsHandler(facades.notifications())),
                                new Route(
                                        "GET",
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
        var channel = serverChannel;
        if (channel == null) {
            throw new IllegalStateException("HTTP server is not started");
        }
        var address = (InetSocketAddress) channel.localAddress();
        return URI.create("http://localhost:" + address.getPort());
    }

    @Override
    public void close() {
        var channel = serverChannel;
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
        bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
    }
}
