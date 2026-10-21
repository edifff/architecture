package ru.archlabs.chat;

import java.util.function.Consumer;

record ActiveSession(ChatSessionImpl session, Consumer<Message> onMessage) {
}
