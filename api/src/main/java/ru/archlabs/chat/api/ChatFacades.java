package ru.archlabs.chat.api;

import io.grpc.ManagedChannel;

public record ChatFacades(
        SessionFacade sessions,
        MessageFacade messages,
        NotificationFacade notifications,
        UserFacade users) {

    public static ChatFacades over(ManagedChannel channel) {
        return new ChatFacades(
                new SessionFacade(channel),
                new MessageFacade(channel),
                new NotificationFacade(channel),
                new UserFacade(channel));
    }
}
