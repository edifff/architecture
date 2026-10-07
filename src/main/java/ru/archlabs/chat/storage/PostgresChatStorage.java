package ru.archlabs.chat.storage;

import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import ru.archlabs.chat.Message;
import ru.archlabs.chat.UserState;
import ru.archlabs.chat.db.tables.records.UsersRecord;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static ru.archlabs.chat.db.Tables.MESSAGES;
import static ru.archlabs.chat.db.Tables.NOTIFICATIONS;
import static ru.archlabs.chat.db.Tables.USERS;

public final class PostgresChatStorage implements ChatStorage {
    private final Connection connection;
    private final DSLContext dsl;

    public PostgresChatStorage(String jdbcUrl, String username, String password) {
        migrate(jdbcUrl, username, password);
        try {
            connection = DriverManager.getConnection(jdbcUrl, username, password);
        } catch (SQLException exception) {
            throw failure("open PostgreSQL chat storage", exception);
        }
        dsl = DSL.using(connection, SQLDialect.POSTGRES);
        resetOnlineUsers();
    }

    @Override
    public void connect(String name, Instant at) {
        transaction(
                () -> {
                    var existing =
                            dsl.selectFrom(USERS).where(USERS.NAME.eq(name)).fetchOptional();
                    if (existing.isPresent()) {
                        if (Boolean.TRUE.equals(existing.orElseThrow().getOnline())) {
                            throw new IllegalStateException("User is already online: " + name);
                        }
                        dsl.update(USERS)
                                .set(USERS.ONLINE, true)
                                .set(USERS.LAST_CONNECTED, utc(at))
                                .where(USERS.NAME.eq(name))
                                .execute();
                    } else {
                        dsl.insertInto(USERS)
                                .columns(
                                        USERS.NAME,
                                        USERS.ONLINE,
                                        USERS.LAST_CONNECTED,
                                        USERS.NOTIFICATIONS_ENABLED)
                                .values(name, true, utc(at), true)
                                .execute();
                    }
                    return null;
                });
    }

    @Override
    public void disconnect(String name, Instant at) {
        transaction(
                () -> {
                    var updated =
                            dsl.update(USERS)
                                    .set(USERS.ONLINE, false)
                                    .set(USERS.LAST_CONNECTED, utc(at))
                                    .where(USERS.NAME.eq(name))
                                    .execute();
                    requireUpdated(updated, name);
                    return null;
                });
    }

    @Override
    public void setNotificationsEnabled(String name, boolean enabled) {
        transaction(
                () -> {
                    var updated =
                            dsl.update(USERS)
                                    .set(USERS.NOTIFICATIONS_ENABLED, enabled)
                                    .where(USERS.NAME.eq(name))
                                    .execute();
                    requireUpdated(updated, name);
                    return null;
                });
    }

    @Override
    public Message append(String sender, String text, Instant sentAt) {
        var storedAt = utc(sentAt);
        return transaction(
                () -> {
                    var record =
                            dsl.insertInto(MESSAGES)
                                    .columns(MESSAGES.SENDER, MESSAGES.TEXT, MESSAGES.SENT_AT)
                                    .values(sender, text, storedAt)
                                    .returning(MESSAGES.ID)
                                    .fetchOne();
                    if (record == null) {
                        throw new StorageException("PostgreSQL did not return a message ID");
                    }
                    // Оповещения для offline-пользователей сохраняются вместе с сообщением:
                    // они переживут аварийное завершение и будут выданы при подключении.
                    dsl.insertInto(NOTIFICATIONS)
                            .columns(
                                    NOTIFICATIONS.RECIPIENT,
                                    NOTIFICATIONS.MESSAGE_ID,
                                    NOTIFICATIONS.CREATED_AT)
                            .select(
                                    dsl.select(
                                                    USERS.NAME,
                                                    DSL.inline(record.getId()),
                                                    DSL.inline(storedAt))
                                            .from(USERS)
                                            .where(USERS.ONLINE.isFalse())
                                            .and(USERS.NOTIFICATIONS_ENABLED.isTrue()))
                            .execute();
                    return new Message(record.getId(), sender, text, storedAt.toInstant());
                });
    }

    @Override
    public List<Message> undeliveredNotifications(String recipient) {
        try {
            return dsl.select(MESSAGES.ID, MESSAGES.SENDER, MESSAGES.TEXT, MESSAGES.SENT_AT)
                    .from(NOTIFICATIONS)
                    .join(MESSAGES)
                    .on(MESSAGES.ID.eq(NOTIFICATIONS.MESSAGE_ID))
                    .where(NOTIFICATIONS.RECIPIENT.eq(recipient))
                    .orderBy(MESSAGES.ID)
                    .fetch(
                            record ->
                                    new Message(
                                            record.get(MESSAGES.ID),
                                            record.get(MESSAGES.SENDER),
                                            record.get(MESSAGES.TEXT),
                                            record.get(MESSAGES.SENT_AT).toInstant()));
        } catch (DataAccessException exception) {
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
                    dsl.deleteFrom(NOTIFICATIONS)
                            .where(NOTIFICATIONS.RECIPIENT.eq(recipient))
                            .and(NOTIFICATIONS.MESSAGE_ID.in(messageIds))
                            .execute();
                    return null;
                });
    }

    @Override
    public List<Message> history() {
        try {
            return dsl.selectFrom(MESSAGES)
                    .orderBy(MESSAGES.ID)
                    .fetch(
                            record ->
                                    new Message(
                                            record.getId(),
                                            record.getSender(),
                                            record.getText(),
                                            record.getSentAt().toInstant()));
        } catch (DataAccessException exception) {
            throw failure("read message history", exception);
        }
    }

    @Override
    public Optional<UserState> user(String name) {
        try {
            return dsl.selectFrom(USERS)
                    .where(USERS.NAME.eq(name))
                    .fetchOptional(PostgresChatStorage::toUserState);
        } catch (DataAccessException exception) {
            throw failure("read user", exception);
        }
    }

    @Override
    public List<UserState> onlineUsersWithNotificationsEnabled() {
        try {
            return dsl.selectFrom(USERS)
                    .where(USERS.ONLINE.isTrue())
                    .and(USERS.NOTIFICATIONS_ENABLED.isTrue())
                    .orderBy(USERS.NAME)
                    .fetch(PostgresChatStorage::toUserState);
        } catch (DataAccessException exception) {
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
        transaction(() -> {dsl.update(USERS).set(USERS.ONLINE, false).execute();
                    return null;});
    }

    private <T> T transaction(Supplier<T> operation) {
        try {
            connection.setAutoCommit(false);
            try {
                var result = operation.get();
                connection.commit();
                return result;
            } catch (DataAccessException exception) {
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

    private static UserState toUserState(UsersRecord record) {
        var lastConnected = record.getLastConnected();
        return new UserState(
                record.getName(),
                Boolean.TRUE.equals(record.getOnline()),
                lastConnected == null ? null : lastConnected.toInstant(),
                Boolean.TRUE.equals(record.getNotificationsEnabled()));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static void requireUpdated(int count, String name) {
        if (count != 1) {
            throw new IllegalArgumentException("Unknown user: " + name);
        }
    }

    private static StorageException failure(String operation, Throwable exception) {
        return new StorageException("Cannot " + operation, exception);
    }
}
