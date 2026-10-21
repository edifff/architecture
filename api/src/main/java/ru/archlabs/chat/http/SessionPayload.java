package ru.archlabs.chat.http;

/** Тело запроса на подключение (используется в ответе). */
public record SessionPayload(String username) {
}
