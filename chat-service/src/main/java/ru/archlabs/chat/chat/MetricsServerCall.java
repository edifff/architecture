package ru.archlabs.chat.chat;

import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.Status;
import ru.archlabs.chat.metrics.RpcMetrics;

/** Записывает результат и латентность вызова при закрытии. */
final class MetricsServerCall<ReqT, RespT>
        extends ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT> {
    private final RpcMetrics metrics;
    private final String service;
    private final long started;

    MetricsServerCall(
            ServerCall<ReqT, RespT> delegate, RpcMetrics metrics, String service, long started) {
        super(delegate);
        this.metrics = metrics;
        this.service = service;
        this.started = started;
    }

    @Override
    public void close(Status status, Metadata trailers) {
        metrics.call(
                service,
                getMethodDescriptor().getFullMethodName(),
                status.getCode().name(),
                System.nanoTime() - started);
        super.close(status, trailers);
    }
}
