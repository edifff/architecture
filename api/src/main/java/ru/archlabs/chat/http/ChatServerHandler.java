package ru.archlabs.chat.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;

final class ChatServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final ChatRequestRouter router;

    ChatServerHandler(ChatRequestRouter router) {
        this.router = router;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        router.handle(ctx, request);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        ConnectionAttribute.closeSubscription(ctx);
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (ctx.channel().attr(ConnectionAttribute.EVENT_SUBSCRIPTION).get() != null) {
            ctx.close();
            return;
        }
        ResponseWriter.error(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Internal server error");
        ctx.close();
    }
}
