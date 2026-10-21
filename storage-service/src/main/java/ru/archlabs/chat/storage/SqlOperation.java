package ru.archlabs.chat.storage;

import java.sql.SQLException;

/** Операция над JDBC, выполняемая в транзакции. */
@FunctionalInterface
interface SqlOperation<T> {
    T get() throws SQLException;
}
