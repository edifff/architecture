package ru.archlabs.chat.api;

/** Пользовательский запрос в том виде, в котором его получает входной элемент. */
public record FacadeRequest(String method, String path, String body) {
}
