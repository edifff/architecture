package ru.archlabs.chat.api;

import ru.archlabs.chat.metrics.ChatMetrics;

/**
 * One facade per logical category: sessions, messages, notifications, users.
 */
public final class ChatFacades {
    private final SessionFacade sessions;
    private final MessageFacade messages;
    private final NotificationFacade notifications;
    private final UserFacade users;
    private final StatusFacade status;
    private final ShardFacade shards;
    private final ChatGateway gateway;

    private ChatFacades(ChatGateway gateway) {
        this.gateway = gateway;
        this.sessions = new SessionFacade(gateway);
        this.messages = new MessageFacade(gateway);
        this.notifications = new NotificationFacade(gateway);
        this.users = new UserFacade(gateway);
        this.status = new StatusFacade(gateway);
        this.shards = new ShardFacade(gateway);
    }

    public static ChatFacades over(ChatGateway gateway) {
        return new ChatFacades(gateway);
    }

    public SessionFacade sessions() {
        return sessions;
    }

    public MessageFacade messages() {
        return messages;
    }

    public NotificationFacade notifications() {
        return notifications;
    }

    public UserFacade users() {
        return users;
    }

    public StatusFacade status() {
        return status;
    }

    public ShardFacade shards() {
        return shards;
    }

    public ChatMetrics metrics() {
        return gateway.metrics();
    }
}
