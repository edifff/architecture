package ru.archlabs.chat.http;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

/** Настраивает конвейер Netty для каждого входящего соединения. */
final class ChatChannelInitializer extends ChannelInitializer<SocketChannel> {
    private final ChatHttpServer server;

    ChatChannelInitializer(ChatHttpServer server) {
        this.server = server;
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        channel.pipeline()
                .addLast(
                        new HttpServerCodec(),
                        new HttpObjectAggregator(1 << 20),
                        new ChatServerHandler(server));
    }
}
