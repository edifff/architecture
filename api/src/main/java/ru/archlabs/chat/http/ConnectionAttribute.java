package ru.archlabs.chat.http;

import io.netty.util.AttributeKey;

final class ConnectionAttribute {
    static final AttributeKey<ClientConnection> KEY = AttributeKey.valueOf("chatConnection");

    private ConnectionAttribute() {
    }
}
