package ru.archlabs.chat.http;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

final class ChatChannelInitializer extends ChannelInitializer<SocketChannel> {
    private final ChatRequestRouter router;

    ChatChannelInitializer(ChatRequestRouter router) {
        this.router = router;
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        channel.pipeline()
                .addLast(new HttpServerCodec())
                .addLast(new HttpObjectAggregator(1 << 20))
                .addLast(new ChatServerHandler(router));
    }
}
