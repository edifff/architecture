package ru.archlabs.chat.storage;

import org.flywaydb.core.Flyway;
import ru.archlabs.chat.Message;
import ru.archlabs.chat.UserState;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public final class PostgresChatStorage implements ChatStorage {
    private static final String SELECT_USER_COLUMNS =
            "SELECT name, online, last_connected, notifications_enabled FROM users ";

    private final Connection connection;

    public PostgresChatStorage(String jdbcUrl, String username, String password) {
        migrate(jdbcUrl, username, password);
        try {
            connection = DriverManager.getConnection(jdbcUrl, username, password);
        } catch (SQLException exception) {
            throw failure("open PostgreSQL chat storage", exception);
        }
        resetOnlineUsers();
    }

    @Override
    public void connect(String name, Instant at) {
        transaction(
                () -> {
                    try (var statement =
                            connection.prepareStatement(
                                    SELECT_USER_COLUMNS + "WHERE name = ?")) {
                        statement.setString(1, name);
                        try (var resultSet = statement.executeQuery()) {
                            if (resultSet.next()) {
                                if (resultSet.getBoolean("online")) {
                                    throw new IllegalStateException(
                                            "User is already online: " + name);
                                }
                                try (var update =
                                        connection.prepareStatement(
                                                "UPDATE users SET online = ?, last_connected = ?"
                                                        + " WHERE name = ?")) {
                                    update.setBoolean(1, true);
                                    update.setObject(2, utc(at));
                                    update.setString(3, name);
                                    update.executeUpdate();
                                }
                            } else {
                                try (var insert =
                                        connection.prepareStatement(
                                                "INSERT INTO users"
                                                        + " (name, online, last_connected,"
                                                        + " notifications_enabled)"
                                                        + " VALUES (?, ?, ?, ?)")) {
                                    insert.setString(1, name);
                                    insert.setBoolean(2, true);
                                    insert.setObject(3, utc(at));
                                    insert.setBoolean(4, true);
                                    insert.executeUpdate();
                                }
                            }
                        }
                    }
                    return null;
                });
    }

    @Override
    public void disconnect(String name, Instant at) {
        transaction(
                () -> {
                    try (var statement =
                            connection.prepareStatement(
                                    "UPDATE users SET online = ?, last_connected = ?"
                                            + " WHERE name = ?")) {
                        statement.setBoolean(1, false);
                        statement.setObject(2, utc(at));
                        statement.setString(3, name);
                        requireUpdated(statement.executeUpdate(), name);
                    }
                    return null;
                });
    }

    @Override
    public void setNotificationsEnabled(String name, boolean enabled) {
        transaction(
                () -> {
                    try (var statement =
                            connection.prepareStatement(
                                    "UPDATE users SET notifications_enabled = ? WHERE name = ?")) {
                        statement.setBoolean(1, enabled);
                        statement.setString(2, name);
                        requireUpdated(statement.executeUpdate(), name);
                    }
                    return null;
                });
    }

    @Override
    public Message append(String sender, String text, Instant sentAt) {
        var storedAt = utc(sentAt);
        return transaction(
                () -> {
                    long id;
                    try (var statement =
                            connection.prepareStatement(
                                    "INSERT INTO messages (sender, text, sent_at)"
                                            + " VALUES (?, ?, ?) RETURNING id")) {
                        statement.setString(1, sender);
                        statement.setString(2, text);
                        statement.setObject(3, storedAt);
                        try (var resultSet = statement.executeQuery()) {
                            if (!resultSet.next()) {
                                throw new StorageException(
                                        "PostgreSQL did not return a message ID");
                            }
                            id = resultSet.getLong(1);
                        }
                    }
                    try (var statement =
                            connection.prepareStatement(
                                    "INSERT INTO notifications (recipient, message_id, created_at)"
                                            + " SELECT name, ?, ? FROM users"
                                            + " WHERE online = ? AND notifications_enabled = ?")) {
                        statement.setLong(1, id);
                        statement.setObject(2, storedAt);
                        statement.setBoolean(3, false);
                        statement.setBoolean(4, true);
                        statement.executeUpdate();
                    }
                    return new Message(id, sender, text, storedAt.toInstant());
                });
    }

    @Override
    public List<Message> undeliveredNotifications(String recipient) {
        try (var statement =
                connection.prepareStatement(
                        "SELECT m.id, m.sender, m.text, m.sent_at"
                                + " FROM notifications n"
                                + " JOIN messages m ON m.id = n.message_id"
                                + " WHERE n.recipient = ? ORDER BY m.id")) {
            statement.setString(1, recipient);
            try (var resultSet = statement.executeQuery()) {
                var messages = new ArrayList<Message>();
                while (resultSet.next()) {
                    messages.add(toMessage(resultSet));
                }
                return messages;
            }
        } catch (SQLException exception) {
            throw failure("read undelivered notifications", exception);
        }
    }

    @Override
    public void clearNotifications(String recipient, List<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return;
        }
        transaction(
                () -> {
                    var placeholders =
                            messageIds.stream().map(id -> "?").collect(Collectors.joining(", "));
                    try (var statement =
                            connection.prepareStatement(
                                    "DELETE FROM notifications"
                                            + " WHERE recipient = ? AND message_id IN ("
                                            + placeholders
                                            + ")")) {
                        statement.setString(1, recipient);
                        for (int i = 0; i < messageIds.size(); i++) {
                            statement.setLong(i + 2, messageIds.get(i));
                        }
                        statement.executeUpdate();
                    }
                    return null;
                });
    }

    @Override
    public List<Message> history() {
        try (var statement =
                connection.prepareStatement("SELECT id, sender, text, sent_at FROM messages"
                        + " ORDER BY id");
                var resultSet = statement.executeQuery()) {
            var messages = new ArrayList<Message>();
            while (resultSet.next()) {
                messages.add(toMessage(resultSet));
            }
            return messages;
        } catch (SQLException exception) {
            throw failure("read message history", exception);
        }
    }

    @Override
    public Optional<UserState> user(String name) {
        try (var statement =
                connection.prepareStatement(SELECT_USER_COLUMNS + "WHERE name = ?")) {
            statement.setString(1, name);
            try (var resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(toUserState(resultSet));
                }
                return Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("read user", exception);
        }
    }

    @Override
    public List<UserState> onlineUsersWithNotificationsEnabled() {
        try (var statement =
                connection.prepareStatement(
                        SELECT_USER_COLUMNS
                                + "WHERE online = ? AND notifications_enabled = ?"
                                + " ORDER BY name")) {
            statement.setBoolean(1, true);
            statement.setBoolean(2, true);
            try (var resultSet = statement.executeQuery()) {
                var users = new ArrayList<UserState>();
                while (resultSet.next()) {
                    users.add(toUserState(resultSet));
                }
                return users;
            }
        } catch (SQLException exception) {
            throw failure("read online users", exception);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException exception) {
            throw failure("close storage", exception);
        }
    }

    private void resetOnlineUsers() {
        transaction(
                () -> {
                    try (var statement =
                            connection.prepareStatement("UPDATE users SET online = ?")) {
                        statement.setBoolean(1, false);
                        statement.executeUpdate();
                    }
                    return null;
                });
    }

    private <T> T transaction(SqlOperation<T> operation) {
        try {
            connection.setAutoCommit(false);
            try {
                var result = operation.get();
                connection.commit();
                return result;
            } catch (SQLException exception) {
                connection.rollback();
                throw failure("execute transaction", exception);
            } catch (RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw failure("execute transaction", exception);
        }
    }

    private static void migrate(String jdbcUrl, String username, String password) {
        Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private static Message toMessage(ResultSet resultSet) throws SQLException {
        return new Message(
                resultSet.getLong("id"),
                resultSet.getString("sender"),
                resultSet.getString("text"),
                resultSet.getTimestamp("sent_at").toInstant());
    }

    private static UserState toUserState(ResultSet resultSet) throws SQLException {
        var lastConnected = resultSet.getTimestamp("last_connected");
        return new UserState(
                resultSet.getString("name"),
                resultSet.getBoolean("online"),
                lastConnected == null ? null : lastConnected.toInstant(),
                resultSet.getBoolean("notifications_enabled"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static void requireUpdated(int count, String name) {
        if (count != 1) {
            throw new IllegalArgumentException("Unknown user: " + name);
        }
    }

    private static StorageException failure(String operation, SQLException exception) {
        return new StorageException("Cannot " + operation, exception);
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T get() throws SQLException;
    }
}
