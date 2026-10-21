package ru.archlabs.chat.chat;

import io.grpc.stub.StreamObserver;
import ru.archlabs.proto.common.Message;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

final class MessageCollector implements StreamObserver<Message> {
    private final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();

    @Override
    public void onNext(Message message) {
        messages.add(message);
    }

    @Override
    public void onError(Throwable throwable) {
    }

    @Override
    public void onCompleted() {
    }

    Message poll(long timeout, TimeUnit unit) throws InterruptedException {
        return messages.poll(timeout, unit);
    }
}
