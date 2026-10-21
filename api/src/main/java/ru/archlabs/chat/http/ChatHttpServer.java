package ru.archlabs.chat.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import ru.archlabs.chat.api.ChatFacade;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class ChatHttpServer implements AutoCloseable {
    private final ChatFacade facade;
    private final int port;
    private final MultiThreadIoEventLoopGroup bossGroup;
    private final MultiThreadIoEventLoopGroup workerGroup;
    private final ServerBootstrap bootstrap;
    private final Map<String, ClientConnection> connections = new ConcurrentHashMap<>();
    private volatile Channel serverChannel;

    public ChatHttpServer(ChatFacade facade, int port) {
        this.facade = Objects.requireNonNull(facade, "facade");
        this.port = port;
        this.bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        this.workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        this.bootstrap =
                new ServerBootstrap()
                        .group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(new ChatChannelInitializer(this));
    }

    public void start() throws Exception {
        serverChannel = bootstrap.bind(port).sync().channel();
    }

    public URI baseUri() {
        var address = (InetSocketAddress) serverChannel.localAddress();
        return URI.create("http://localhost:" + address.getPort());
    }

    ChatFacade facade() {
        return facade;
    }

    synchronized void open(String username) {
        if (connections.containsKey(username)) {
            throw new IllegalStateException("User is already connected: " + username);
        }
        connections.put(username, new ClientConnection(username));
    }

    synchronized void closeConnection(String username) {
        var connection = connections.remove(username);
        if (connection != null && connection.subscription() != null) {
            connection.subscription().close();
        }
    }

    ClientConnection connection(String username) {
        return connections.get(username);
    }

    @Override
    public void close() {
        try {
            if (serverChannel != null) {
                serverChannel.close().syncUninterruptibly();
            }
            bossGroup.shutdownGracefully(0, 15, TimeUnit.SECONDS).syncUninterruptibly();
            workerGroup.shutdownGracefully(0, 15, TimeUnit.SECONDS).syncUninterruptibly();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Cannot stop HTTP chat server", exception);
        }
    }
}
