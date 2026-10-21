package ru.archlabs.chat.http;

/** Тело запроса на переключение уведомлений. */
public record NotificationsRequest(boolean enabled) {
}
