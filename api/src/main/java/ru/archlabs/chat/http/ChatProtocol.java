package ru.archlabs.chat.http;

/** Константы клиентского HTTP-протокола. */
public final class ChatProtocol {
    public static final String CONTENT_TYPE_JSON = "application/json";
    public static final String CONTENT_TYPE_EVENT_STREAM = "text/event-stream";
    public static final String MESSAGE_EVENT = "message";
    public static final String DATA_PREFIX = "data:";

    private ChatProtocol() {
    }
}
