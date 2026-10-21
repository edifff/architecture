package ru.archlabs.chat.api;

import io.grpc.ManagedChannel;
import ru.archlabs.proto.chat.ChatServiceGrpc;
import ru.archlabs.proto.chat.HistoryRequest;
import ru.archlabs.proto.chat.SendRequest;
import ru.archlabs.proto.common.Message;

import java.util.List;

public final class MessageFacade {
    private final ChatServiceGrpc.ChatServiceBlockingStub blocking;

    public MessageFacade(ManagedChannel channel) {
        this.blocking = ChatServiceGrpc.newBlockingStub(channel);
    }

    public Message send(String username, String body) {
        var request = ChatJson.read(body, SendMessageRequest.class);
        FacadeSupport.requireUsername(username);
        FacadeSupport.requireText(request.text());
        return blocking.send(
                SendRequest.newBuilder().setName(username).setText(request.text()).build());
    }

    public List<Message> history(String username) {
        FacadeSupport.requireUsername(username);
        return blocking.history(HistoryRequest.newBuilder().setName(username).build())
                .getMessagesList();
    }
}
