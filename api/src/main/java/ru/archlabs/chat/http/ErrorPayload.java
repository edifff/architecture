package ru.archlabs.chat.http;

/** Тело ошибки клиентского протокола. */
public record ErrorPayload(String error) {
}
