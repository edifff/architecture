package ru.archlabs.chat.chat;

import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import ru.archlabs.chat.metrics.RpcMetrics;

/** Считает число и латентность gRPC-вызовов доменного сервиса. */
final class MetricsInterceptor implements ServerInterceptor {
    private static final String SERVICE = "chat";
    private final RpcMetrics metrics;

    MetricsInterceptor(RpcMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        var started = System.nanoTime();
        var forwarding =
                new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                    @Override
                    public void close(Status status, Metadata trailers) {
                        metrics.call(
                                SERVICE,
                                call.getMethodDescriptor().getFullMethodName(),
                                status.getCode().name(),
                                System.nanoTime() - started);
                        super.close(status, trailers);
                    }
                };
        return next.startCall(forwarding, headers);
    }
}
