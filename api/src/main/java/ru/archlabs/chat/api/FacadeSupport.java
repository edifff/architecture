package ru.archlabs.chat.api;

final class FacadeSupport {
    private FacadeSupport() {
    }

    static void requireUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username must not be blank");
        }
    }

    static void requireText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Message must not be blank");
        }
    }
}
