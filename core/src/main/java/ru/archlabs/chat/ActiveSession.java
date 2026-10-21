package ru.archlabs.chat;

import java.util.function.Consumer;

/** Активная сессия пользователя и обработчик доставки. */
record ActiveSession(ChatSessionImpl session, Consumer<Message> onMessage) {
}
