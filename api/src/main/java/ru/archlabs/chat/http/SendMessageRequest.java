package ru.archlabs.chat.http;

/** Тело запроса на отправку сообщения. */
public record SendMessageRequest(String text) {
}
