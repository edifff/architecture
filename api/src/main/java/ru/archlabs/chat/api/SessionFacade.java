package ru.archlabs.chat.api;

import io.grpc.ManagedChannel;
import ru.archlabs.proto.chat.ChatServiceGrpc;
import ru.archlabs.proto.chat.ConnectRequest;
import ru.archlabs.proto.chat.DisconnectRequest;
import ru.archlabs.proto.common.UserState;

public final class SessionFacade {
    private final ChatServiceGrpc.ChatServiceBlockingStub blocking;

    public SessionFacade(ManagedChannel channel) {
        this.blocking = ChatServiceGrpc.newBlockingStub(channel);
    }

    public UserState connect(String username) {
        FacadeSupport.requireUsername(username);
        return blocking.connect(ConnectRequest.newBuilder().setName(username).build()).getUser();
    }

    public void disconnect(String username) {
        FacadeSupport.requireUsername(username);
        blocking.disconnect(DisconnectRequest.newBuilder().setName(username).build());
    }
}
