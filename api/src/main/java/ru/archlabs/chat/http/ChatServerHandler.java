package ru.archlabs.chat.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;

final class ChatServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final ChatRequestRouter router;

    ChatServerHandler(ChatRequestRouter router) {
        this.router = router;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        router.dispatch(ctx, request);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        var connection = ctx.channel().attr(ConnectionAttribute.KEY).get();
        if (connection != null) {
            connection.cancelSse();
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
