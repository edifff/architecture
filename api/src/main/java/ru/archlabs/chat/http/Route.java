package ru.archlabs.chat.http;

/** Разобранный маршрут {@code /{section}/{username}}. */
record Route(String section, String username) {
}
