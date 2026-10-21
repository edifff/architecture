package ru.archlabs.chat;

/** Доменный слой без транспорта: ChatServerImpl поверх in-memory хранилища. */
final class InMemoryChatServerTest extends ChatServerContractTest {
    private ChatServer server;

    @Override
    ChatServer createServer() {
        server = new ChatServerImpl(new InMemoryChatStorage());
        return server;
    }

    @Override
    void resetDatabase() {
    }

    @Override
    void closeEnvironment() {
        if (server != null) {
            server.close();
        }
    }
}
