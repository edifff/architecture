package ru.archlabs.chat.api;

import io.grpc.ManagedChannel;
import ru.archlabs.proto.chat.ChatServiceGrpc;
import ru.archlabs.proto.chat.UserRequest;
import ru.archlabs.proto.common.UserState;

public final class UserFacade {
    private final ChatServiceGrpc.ChatServiceBlockingStub blocking;

    public UserFacade(ManagedChannel channel) {
        this.blocking = ChatServiceGrpc.newBlockingStub(channel);
    }

    public UserState user(String username) {
        FacadeSupport.requireUsername(username);
        return blocking.user(UserRequest.newBuilder().setName(username).build());
    }
}
