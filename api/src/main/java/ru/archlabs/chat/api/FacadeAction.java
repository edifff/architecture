package ru.archlabs.chat.api;

/** Что транспорт должен сделать с соединением после обработки запроса. */
public enum FacadeAction {
    NONE,
    OPEN,
    CLOSE,
    STREAM
}
