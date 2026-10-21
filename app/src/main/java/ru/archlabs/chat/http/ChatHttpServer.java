package ru.archlabs.chat.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import ru.archlabs.chat.ChatMetrics;
import ru.archlabs.chat.ChatServer;
import ru.archlabs.chat.UserState;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP-сервер чата на Netty. Публичный интерфейс ({@code start}, {@code baseUri},
 * {@code close}) и операции {@code connect}/{@code disconnect}/{@code connection}/{@code user}
 * не изменились; вместо Jetty и сервлетов запросы обрабатывает {@link ChatServerHandler}.
 */
public final class ChatHttpServer implements AutoCloseable {
    private final ChatServer chat;
    private final ChatMetrics metrics;
    private final int port;
    private final MultiThreadIoEventLoopGroup bossGroup;
    private final MultiThreadIoEventLoopGroup workerGroup;
    private final ServerBootstrap bootstrap;
    private final Map<String, ClientConnection> connections = new ConcurrentHashMap<>();
    private volatile Channel serverChannel;

    public ChatHttpServer(ChatServer chat, int port) {
        this.chat = Objects.requireNonNull(chat, "chat");
        this.port = port;
        this.metrics = chat instanceof ChatMetrics.Source source ? source.metrics() : new ChatMetrics();
        this.bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        this.workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        this.bootstrap =
                new ServerBootstrap()
                        .group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(
                                new ChannelInitializer<SocketChannel>() {
                                    @Override
                                    protected void initChannel(SocketChannel channel) {
                                        channel
                                                .pipeline()
                                                .addLast(
                                                        new HttpServerCodec(),
                                                        new HttpObjectAggregator(1 << 20),
                                                        new ChatServerHandler(ChatHttpServer.this));
                                    }
                                });
    }

    public void start() throws Exception {
        serverChannel = bootstrap.bind(port).sync().channel();
    }

    public URI baseUri() {
        var address = (InetSocketAddress) serverChannel.localAddress();
        return URI.create("http://localhost:" + address.getPort());
    }

    public ChatServer chat() {
        return chat;
    }

    ChatMetrics metrics() {
        return metrics;
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
            if (serverChannel != null) {
                serverChannel.close().syncUninterruptibly();
            }
            bossGroup.shutdownGracefully().syncUninterruptibly();
            workerGroup.shutdownGracefully().syncUninterruptibly();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Cannot stop HTTP chat server", exception);
        }
        chat.close();
    }
}
