package ru.archlabs.chat.api;

/**
 * Пользовательское сообщение во внешнем (текстовом) формате — то, что «входной»
 * элемент получает до конвертации в protobuf.
 *
 * <p>Формат строки: {@code <OPERATION> <username> [аргумент...]}.
 * Поддерживаются операции {@code CONNECT}, {@code DISCONNECT}, {@code SEND},
 * {@code HISTORY}, {@code USER}, {@code SET_NOTIFICATIONS}.
 */
public record UserMessage(Operation operation, String username, String text, Boolean enabled) {
    /** Разбирает строку пользователя; синтаксические ошибки — {@link IllegalArgumentException}. */
    public static UserMessage parse(String line) {
        if (line == null || line.isBlank()) {
            throw new IllegalArgumentException("Message must not be blank");
        }
        var parts = line.trim().split("\\s+", 3);
        if (parts.length < 2) {
            throw new IllegalArgumentException("Expected <operation> <username>");
        }
        Operation operation;
        try {
            operation = Operation.valueOf(parts[0].toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown operation: " + parts[0]);
        }
        var username = parts[1];
        var argument = parts.length == 3 ? parts[2] : null;
        return switch (operation) {
            case SEND -> new UserMessage(operation, username, argument, null);
            case SET_NOTIFICATIONS ->
                    new UserMessage(operation, username, null, parseBoolean(argument));
            default -> new UserMessage(operation, username, null, null);
        };
    }

    private static Boolean parseBoolean(String value) {
        if (value == null) {
            throw new IllegalArgumentException("SET_NOTIFICATIONS requires true or false");
        }
        if (value.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (value.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("Expected true or false, got: " + value);
    }
}
