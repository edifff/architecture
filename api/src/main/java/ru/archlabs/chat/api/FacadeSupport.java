package ru.archlabs.chat.api;

import ru.archlabs.chat.domain.ChatValidationException;

final class FacadeSupport {
    private FacadeSupport() {
    }

    static String requireUser(String user) {
        if (user == null || user.isBlank()) {
            throw new ChatValidationException("Username must not be blank");
        }
        return user.strip();
    }

    static String requireText(String text) {
        if (text == null || text.isBlank()) {
            throw new ChatValidationException("Message must not be blank");
        }
        return text.strip();
    }

    static int parseLimit(String value) {
        if (value == null || value.isBlank()) {
            return 50;
        }
        try {
            var limit = Integer.parseInt(value);
            if (limit <= 0) {
                throw new ChatValidationException("History limit must be positive");
            }
            return Math.min(limit, 1000);
        } catch (NumberFormatException exception) {
            throw new ChatValidationException("History limit must be a number");
        }
    }
}
