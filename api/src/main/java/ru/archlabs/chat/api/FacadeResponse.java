package ru.archlabs.chat.api;

/** Результат работы входного элемента: HTTP-статус, тело и действие с соединением. */
public record FacadeResponse(
        int status, String contentType, String body, FacadeAction action, String username) {

    public static FacadeResponse json(int status, Object payload) {
        return new FacadeResponse(
                status, ChatProtocol.CONTENT_TYPE_JSON, ChatJson.write(payload), FacadeAction.NONE, null);
    }

    public static FacadeResponse error(int status, String message) {
        return new FacadeResponse(
                status,
                ChatProtocol.CONTENT_TYPE_JSON,
                ChatJson.write(new ErrorPayload(message)),
                FacadeAction.NONE,
                null);
    }

    public static FacadeResponse empty(int status) {
        return new FacadeResponse(status, null, "", FacadeAction.NONE, null);
    }

    public static FacadeResponse opened(String username) {
        return new FacadeResponse(
                201,
                ChatProtocol.CONTENT_TYPE_JSON,
                ChatJson.write(new SessionPayload(username)),
                FacadeAction.OPEN,
                username);
    }

    public static FacadeResponse closed(String username) {
        return new FacadeResponse(204, null, "", FacadeAction.CLOSE, username);
    }

    public static FacadeResponse stream(String username) {
        return new FacadeResponse(
                200, ChatProtocol.CONTENT_TYPE_EVENT_STREAM, "", FacadeAction.STREAM, username);
    }
}
