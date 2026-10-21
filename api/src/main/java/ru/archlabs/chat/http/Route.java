package ru.archlabs.chat.http;

import java.util.Set;

record Route(String section, String username) {
    private static final Set<String> SECTIONS =
            Set.of("sessions", "users", "messages", "notifications", "events");

    static Route parse(String path) {
        if (path == null || !path.startsWith("/") || path.length() <= 1) {
            return null;
        }
        var parts = path.substring(1).split("/", -1);
        if (parts.length == 1) {
            if (SECTIONS.contains(parts[0])) {
                throw new IllegalArgumentException("Username must not be blank");
            }
            return null;
        }
        if (parts.length != 2) {
            return null;
        }
        var section = parts[0];
        var username = parts[1];
        if (!SECTIONS.contains(section)) {
            return null;
        }
        if (username.isBlank()) {
            throw new IllegalArgumentException("Username must not be blank");
        }
        return new Route(section, username);
    }
}
