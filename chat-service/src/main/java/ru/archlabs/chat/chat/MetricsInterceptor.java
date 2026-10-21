package ru.archlabs.chat.chat;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import ru.archlabs.chat.metrics.RpcMetrics;

final class MetricsInterceptor implements ServerInterceptor {
    private static final String SERVICE = "chat";
    private final RpcMetrics metrics;

    MetricsInterceptor(RpcMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        return next.startCall(
                new MetricsServerCall<>(call, metrics, SERVICE, System.nanoTime()), headers);
    }
}
