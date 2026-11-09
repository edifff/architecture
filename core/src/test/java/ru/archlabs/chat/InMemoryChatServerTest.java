package ru.archlabs.chat;

import ru.archlabs.chat.service.ChatServerImpl;

final class InMemoryChatServerTest extends ChatServerContractTest {
    private ChatServer server;

    @Override
    ChatServer createServer() {
        server = new ChatServerImpl();
        return server;
    }

    @Override
    void closeEnvironment() {
        if (server != null) {
            server.close();
        }
    }
}
